/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.testing.node

import com.google.common.collect.MutableClassToInstanceMap
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.contracts.ContractClassName
import net.corda.core.contracts.StateRef
import net.corda.core.cordapp.CordappProvider
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.FlowProgressHandle
import net.corda.core.node.*
import net.corda.core.node.services.*
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.ServicesForResolutionImpl
import net.corda.node.internal.configureDatabase
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.services.api.SchemaService
import net.corda.node.services.api.VaultServiceInternal
import net.corda.node.services.api.WritableTransactionStorage
import net.corda.node.services.config.ConfigHelper
import net.corda.node.services.config.configOf
import net.corda.node.services.config.parseToDbSchemaFriendlyName
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.node.services.schema.HibernateObserver
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.transactions.InMemoryTransactionVerifierService
import net.corda.node.services.vault.NodeVaultService
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.HibernateConfiguration
import net.corda.nodeapi.internal.persistence.TransactionIsolationLevel
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.database.DatabaseConstants
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_CLASSNAME
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_PASSWORD
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_URL
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_USER
import net.corda.testing.database.DatabaseConstants.SCHEMA
import net.corda.testing.database.DatabaseConstants.TRANSACTION_ISOLATION_LEVEL
import net.corda.testing.internal.DEV_ROOT_CA
import net.corda.testing.internal.MockCordappProvider
import net.corda.testing.node.internal.MockKeyManagementService
import net.corda.testing.node.internal.MockTransactionStorage
import net.corda.testing.services.MockAttachmentStorage
import java.security.KeyPair
import java.sql.Connection
import java.time.Clock
import java.util.*

/**
 * Returns a simple [InMemoryIdentityService] containing the supplied [identities].
 */
fun makeTestIdentityService(vararg identities: PartyAndCertificate) = InMemoryIdentityService(identities, DEV_ROOT_CA.certificate)

/**
 * An implementation of [ServiceHub] that is designed for in-memory unit tests of contract validation logic. It has
 * enough functionality to do tests of code that queries the vault, inserts to the vault, and constructs/checks
 * transactions. However it isn't enough to test flows and other aspects of an app that require a network. For that
 * you should investigate [MockNetwork].
 *
 * There are a variety of constructors that can be used to supply enough data to simulate a node. Each mock service hub
 * must have at least an identity of its own. The other components have defaults that work in most situations.
 */
open class MockServices private constructor(
        cordappLoader: CordappLoader,
        override val validatedTransactions: TransactionStorage,
        override val identityService: IdentityService,
        final override val networkParameters: NetworkParameters,
        private val initialIdentity: TestIdentity,
        private val moreKeys: Array<out KeyPair>
) : ServiceHub {
    companion object {
        /**
         * Make properties appropriate for creating a DataSource for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database or database username/schema.  Defaults to a random string.
         * @param nodeNameExtension Provides additional name extension for the "instance" of in-memory database to provide uniqueness when running unit tests against H2 db.
         * @param configSupplier returns [Config] with dataSourceProperties entry.
         */
        // TODO: Can we use an X509 principal generator here?
        @JvmStatic
        fun makeTestDataSourceProperties(nodeName: String = SecureHash.randomSHA256().toString(), nodeNameExtension: String? = null,
                                         configSupplier: (String, String?) -> Config = ::databaseProviderDataSourceConfig): Properties {
            val config = configSupplier(nodeName, nodeNameExtension)
            val props = Properties()
            props.setProperty("dataSourceClassName", config.getString("dataSourceProperties.dataSourceClassName"))
            props.setProperty("dataSource.url", config.getString("dataSourceProperties.dataSource.url"))
            props.setProperty("dataSource.user", config.getString("dataSourceProperties.dataSource.user"))
            props.setProperty("dataSource.password", config.getString("dataSourceProperties.dataSource.password"))
            props["autoCommit"] = false
            return props
        }

        /**
         * Make properties appropriate for creating a Database for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database or database username/schema.
         */
        @JvmStatic
        fun makeTestDatabaseProperties(nodeName: String? = null): DatabaseConfig {
            val config = databaseProviderDataSourceConfig(nodeName)
            val transactionIsolationLevel = if (config.hasPath(TRANSACTION_ISOLATION_LEVEL)) TransactionIsolationLevel.valueOf(config.getString(TRANSACTION_ISOLATION_LEVEL))
                                                else TransactionIsolationLevel.READ_COMMITTED
            val schema = if (config.hasPath(SCHEMA)) config.getString(SCHEMA) else ""
            return DatabaseConfig(runMigration = true, transactionIsolationLevel = transactionIsolationLevel, schema = schema)
        }

        /**
         * Makes database and mock services appropriate for unit tests.
         *
         * @param cordappPackages A [List] of cordapp packages to scan for any cordapp code, e.g. contract verification code, flows and services.
         * @param identityService An instance of [IdentityService], see [makeTestIdentityService].
         * @param initialIdentity The first (typically sole) identity the services will represent.
         * @param moreKeys A list of additional [KeyPair] instances to be used by [MockServices].
         * @return A pair where the first element is the instance of [CordaPersistence] and the second is [MockServices].
         */
        @JvmStatic
        @JvmOverloads
        fun makeTestDatabaseAndMockServices(cordappPackages: List<String>,
                                            identityService: IdentityService,
                                            initialIdentity: TestIdentity,
                                            networkParameters: NetworkParameters = testNetworkParameters(),
                                            vararg moreKeys: KeyPair): Pair<CordaPersistence, MockServices> {
            val cordappLoader = CordappLoader.createWithTestPackages(cordappPackages)
            val dataSourceProps = makeTestDataSourceProperties(initialIdentity.name.organisation)
            val schemaService = NodeSchemaService(cordappLoader.cordappSchemas)
            val database = configureDatabase(dataSourceProps, makeTestDatabaseProperties(initialIdentity.name.organisation), identityService, schemaService)
            val mockService = database.transaction {
                object : MockServices(cordappLoader, identityService, networkParameters, initialIdentity, moreKeys) {
                    override val vaultService: VaultService = makeVaultService(database.hibernateConfig, schemaService)

                    override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
                        super.recordTransactions(statesToRecord, txs)
                        // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                        (vaultService as VaultServiceInternal).notifyAll(statesToRecord, txs.map { it.coreTransaction })
                    }

                    override fun jdbcSession(): Connection = database.createSession()
                }
            }
            return Pair(database, mockService)
        }

        @JvmStatic
        private fun getCallerPackage(): String {
            // TODO: In Java 9 there's a new stack walker API that is better than this.
            // The magic number '3' here is to chop off this method, an invisible bridge method generated by the
            // compiler and then the c'tor itself.
            return Throwable().stackTrace[3].className.split('.').dropLast(1).joinToString(".")
        }
    }

    private constructor(cordappLoader: CordappLoader, identityService: IdentityService, networkParameters: NetworkParameters,
                        initialIdentity: TestIdentity, moreKeys: Array<out KeyPair>)
            : this(cordappLoader, MockTransactionStorage(), identityService, networkParameters, initialIdentity, moreKeys)

    /**
     * Create a mock [ServiceHub] that looks for app code in the given package names, uses the provided identity service
     * (you can get one from [makeTestIdentityService]) and represents the given identity.
     */
    @JvmOverloads
    constructor(cordappPackages: List<String>,
                initialIdentity: TestIdentity,
                identityService: IdentityService = makeTestIdentityService(),
                vararg moreKeys: KeyPair) :
            this(CordappLoader.createWithTestPackages(cordappPackages), identityService, testNetworkParameters(), initialIdentity, moreKeys)

    constructor(cordappPackages: List<String>,
                initialIdentity: TestIdentity,
                identityService: IdentityService,
                networkParameters: NetworkParameters,
                vararg moreKeys: KeyPair) :
            this(CordappLoader.createWithTestPackages(cordappPackages), identityService, networkParameters, initialIdentity, moreKeys)

    /**
     * Create a mock [ServiceHub] that looks for app code in the given package names, uses the provided identity service
     * (you can get one from [makeTestIdentityService]) and represents the given identity.
     */
    @JvmOverloads
    constructor(cordappPackages: List<String>, initialIdentityName: CordaX500Name, identityService: IdentityService = makeTestIdentityService(), key: KeyPair, vararg moreKeys: KeyPair) :
            this(cordappPackages, TestIdentity(initialIdentityName, key), identityService, *moreKeys)

    /**
     * Create a mock [ServiceHub] that can't load CorDapp code, which uses the provided identity service
     * (you can get one from [makeTestIdentityService]) and which represents the given identity.
     */
    @JvmOverloads
    constructor(cordappPackages: List<String>, initialIdentityName: CordaX500Name, identityService: IdentityService = makeTestIdentityService()) :
            this(cordappPackages, TestIdentity(initialIdentityName), identityService)

    /**
     * Create a mock [ServiceHub] that can't load CorDapp code, and which uses a default service identity.
     */
    constructor(cordappPackages: List<String>) : this(cordappPackages, CordaX500Name("TestIdentity", "", "GB"), makeTestIdentityService())

    /**
     * Create a mock [ServiceHub] which uses the package of the caller to find CorDapp code. It uses the provided identity service
     * (you can get one from [makeTestIdentityService]) and which represents the given identity.
     */
    @JvmOverloads
    constructor(initialIdentityName: CordaX500Name, identityService: IdentityService = makeTestIdentityService(), key: KeyPair, vararg moreKeys: KeyPair)
            : this(listOf(getCallerPackage()), TestIdentity(initialIdentityName, key), identityService, *moreKeys)

    /**
     * Create a mock [ServiceHub] which uses the package of the caller to find CorDapp code. It uses the provided identity service
     * (you can get one from [makeTestIdentityService]) and which represents the given identity. It has no keys.
     */
    @JvmOverloads
    constructor(initialIdentityName: CordaX500Name, identityService: IdentityService = makeTestIdentityService())
            : this(listOf(getCallerPackage()), TestIdentity(initialIdentityName), identityService)

    /**
     * A helper constructor that requires at least one test identity to be registered, and which takes the package of
     * the caller as the package in which to find app code. This is the most convenient constructor and the one that
     * is normally worth using. The first identity is the identity of this service hub, the rest are identities that
     * it is aware of.
     */
    constructor(firstIdentity: TestIdentity, vararg moreIdentities: TestIdentity) : this(
            listOf(getCallerPackage()),
            firstIdentity,
            makeTestIdentityService(*listOf(firstIdentity, *moreIdentities).map { it.identity }.toTypedArray()),
            firstIdentity.keyPair
    )

    /**
     * Create a mock [ServiceHub] which uses the package of the caller to find CorDapp code. It uses a default service
     * identity.
     */
    constructor() : this(listOf(getCallerPackage()), CordaX500Name("TestIdentity", "", "GB"), makeTestIdentityService())

    override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
        txs.forEach {
            (validatedTransactions as WritableTransactionStorage).addTransaction(it)
        }
    }

    final override val attachments = MockAttachmentStorage()
    override val keyManagementService: KeyManagementService by lazy { MockKeyManagementService(identityService, *arrayOf(initialIdentity.keyPair) + moreKeys) }
    override val vaultService: VaultService get() = throw UnsupportedOperationException()
    override val contractUpgradeService: ContractUpgradeService get() = throw UnsupportedOperationException()
    override val networkMapCache: NetworkMapCache get() = throw UnsupportedOperationException()
    override val clock: Clock get() = Clock.systemUTC()
    override val myInfo: NodeInfo
        get() {
            return NodeInfo(listOf(NetworkHostAndPort("mock.node.services", 10000)), listOf(initialIdentity.identity), 1, serial = 1L)
        }
    override val transactionVerifierService: TransactionVerifierService get() = InMemoryTransactionVerifierService(2)
    private val mockCordappProvider: MockCordappProvider = MockCordappProvider(cordappLoader, attachments, networkParameters.whitelistedContractImplementations)
    override val cordappProvider: CordappProvider get() = mockCordappProvider

    protected val servicesForResolution: ServicesForResolution get() = ServicesForResolutionImpl(identityService, attachments, cordappProvider, networkParameters, validatedTransactions)

    internal fun makeVaultService(hibernateConfig: HibernateConfiguration, schemaService: SchemaService): VaultServiceInternal {
        val vaultService = NodeVaultService(Clock.systemUTC(), keyManagementService, servicesForResolution, hibernateConfig)
        HibernateObserver.install(vaultService.rawUpdates, hibernateConfig, schemaService)
        return vaultService
    }

    // This needs to be internal as MutableClassToInstanceMap is a guava type and shouldn't be part of our public API
    /** A map of available [CordaService] implementations */
    internal val cordappServices: MutableClassToInstanceMap<SerializeAsToken> = MutableClassToInstanceMap.create<SerializeAsToken>()

    override fun <T : SerializeAsToken> cordaService(type: Class<T>): T {
        require(type.isAnnotationPresent(CordaService::class.java)) { "${type.name} is not a Corda service" }
        return cordappServices.getInstance(type)
                ?: throw IllegalArgumentException("Corda service ${type.name} does not exist")
    }

    override fun jdbcSession(): Connection = throw UnsupportedOperationException()

    override fun registerUnloadHandler(runOnStop: () -> Unit) = throw UnsupportedOperationException()

    /** Add the given package name to the list of packages which will be scanned for cordapp contract verification code */
    fun addMockCordapp(contractClassName: ContractClassName) {
        mockCordappProvider.addMockCordapp(contractClassName, attachments)
    }

    override fun loadState(stateRef: StateRef) = servicesForResolution.loadState(stateRef)
    override fun loadStates(stateRefs: Set<StateRef>) = servicesForResolution.loadStates(stateRefs)
}

/**
 * Function which can be used to create a mock [CordaService] for use within testing, such as an Oracle.
 */
fun <T : SerializeAsToken> createMockCordaService(serviceHub: MockServices, serviceConstructor: (AppServiceHub) -> T): T {
    class MockAppServiceHubImpl<out T : SerializeAsToken>(val serviceHub: MockServices, serviceConstructor: (AppServiceHub) -> T) : AppServiceHub, ServiceHub by serviceHub {
        val serviceInstance: T = serviceConstructor(this)

        init {
            serviceHub.cordappServices.putInstance(serviceInstance.javaClass, serviceInstance)
        }

        override fun <T> startFlow(flow: FlowLogic<T>): FlowHandle<T> {
            throw UnsupportedOperationException()
        }

        override fun <T> startTrackedFlow(flow: FlowLogic<T>): FlowProgressHandle<T> {
            throw UnsupportedOperationException()
        }
    }
    return MockAppServiceHubImpl(serviceHub, serviceConstructor).serviceInstance
}

/**
 * Reads database and dataSource configuration from a file denoted by 'databaseProvider' system property,
 * overwitten by system properties and defaults to H2 in memory db.
 * @param nodeName Reflects the "instance" of the database username/schema, the value will be used to replace ${custom.nodeOrganizationName} placeholder
 * if the placeholder is present in config.
 * @param postfix Additional postix added to database "instance" name in case config defaults to H2 in memory database.
 */
fun databaseProviderDataSourceConfig(nodeName: String? = null, postfix: String? = null): Config {

    val parseOptions = ConfigParseOptions.defaults()

    //read overrides from command line (passed by Gradle as system properties)
    val systemConfigOverride = ConfigFactory.parseMap(System.getProperties().filterKeys { (it as String).startsWith(ConfigHelper.CORDA_PROPERTY_PREFIX) }
            .mapKeys { (it.key as String).removePrefix(ConfigHelper.CORDA_PROPERTY_PREFIX) }
            .filterKeys { listOf(DATA_SOURCE_URL, DATA_SOURCE_CLASSNAME, DATA_SOURCE_USER, DATA_SOURCE_PASSWORD).contains(it) })

    //read from db vendor specific configuration file
    val databaseConfig = ConfigFactory.parseResources(System.getProperty("custom.databaseProvider") + ".conf", parseOptions.setAllowMissing(true))
    val fixedOverride = ConfigFactory.parseString("baseDirectory = \"\"")

    //implied property custom.nodeOrganizationName to fill the potential placeholders in db schema/ db user properties
    val nodeOrganizationNameConfig = if (nodeName != null) configOf("custom.nodeOrganizationName" to parseToDbSchemaFriendlyName(nodeName)) else ConfigFactory.empty()

    //defaults to H2
    //for H2 the same db instance runs for all integration tests, so adding additional variable postfix create a unique database each time
    val defaultConfig = inMemoryH2DataSourceConfig(nodeName, postfix)

    return systemConfigOverride.withFallback(databaseConfig)
            .withFallback(fixedOverride)
            .withFallback(nodeOrganizationNameConfig)
            .withFallback(defaultConfig)
            .resolve()
}

/**
 * Creates data source configuration for in memory H2 as it would be specified in reference.conf 'datasource' snippet.
 * @param nodeName Reflects the "instance" of the database username/schema
 * @param postfix Additional postix added to database "instance" name to add uniqueness when running integration tests.
 */
fun inMemoryH2DataSourceConfig(nodeName: String? = null, postfix: String? = null) : Config {
    val nodeName = nodeName ?: SecureHash.randomSHA256().toString()
    val h2InstanceName = if (postfix != null) nodeName + "_" + postfix else nodeName

    return ConfigFactory.parseMap(mapOf(
            DatabaseConstants.DATA_SOURCE_CLASSNAME to "org.h2.jdbcx.JdbcDataSource",
            DatabaseConstants.DATA_SOURCE_URL to "jdbc:h2:mem:${h2InstanceName}_persistence;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE",
            DatabaseConstants.DATA_SOURCE_USER to "sa",
            DatabaseConstants.DATA_SOURCE_PASSWORD to ""))
}