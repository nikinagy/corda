package net.corda.node.services.rpc

import net.corda.core.context.InvocationContext
import net.corda.node.internal.security.AuthorizingSubject

data class RpcAuthContext(val invocation: InvocationContext,
                          private val authorizer: AuthorizingSubject)
    : AuthorizingSubject by authorizer

