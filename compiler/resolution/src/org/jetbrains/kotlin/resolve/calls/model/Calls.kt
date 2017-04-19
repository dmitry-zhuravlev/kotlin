/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.model

import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.ASTCallKind


interface ASTCall {
    val callKind: ASTCallKind

    val explicitReceiver: ReceiverCallArgument?

    // a.(foo)() -- (foo) is dispatchReceiverForInvoke
    val dispatchReceiverForInvokeExtension: SimpleCallArgument? get() = null

    val name: Name

    val typeArguments: List<TypeArgument>

    val argumentsInParenthesis: List<CallArgument>

    val externalArgument: CallArgument?

    val isInfixCall: Boolean
    val isOperatorCall: Boolean
    val isSuperOrDelegatingConstructorCall: Boolean
}

private fun SimpleCallArgument.checkReceiverInvariants() {
    assert(!isSpread) {
        "Receiver cannot be a spread: $this"
    }
    assert(argumentName == null) {
        "Argument name should be null for receiver: $this, but it is $argumentName"
    }
}

fun ASTCall.checkCallInvariants() {
    assert(explicitReceiver !is LambdaArgument && explicitReceiver !is CallableReferenceArgument) {
        "Lambda argument or callable reference is not allowed as explicit receiver: $explicitReceiver"
    }

    (explicitReceiver as? SimpleCallArgument)?.checkReceiverInvariants()
    dispatchReceiverForInvokeExtension?.checkReceiverInvariants()

    if (callKind != ASTCallKind.FUNCTION) {
        assert(externalArgument == null) {
            "External argument is not allowed not for function call: $externalArgument."
        }
        assert(argumentsInParenthesis.isEmpty()) {
            "Arguments in parenthesis should be empty for not function call: $this "
        }
        assert(dispatchReceiverForInvokeExtension == null) {
            "Dispatch receiver for invoke should be null for not function call: $dispatchReceiverForInvokeExtension"
        }
    }
    else {
        assert(externalArgument == null || !externalArgument!!.isSpread) {
            "External argument cannot nave spread element: $externalArgument"
        }

        assert(externalArgument?.argumentName == null) {
            "Illegal external argument with name: $externalArgument"
        }

        assert(dispatchReceiverForInvokeExtension == null || !dispatchReceiverForInvokeExtension!!.isSafeCall) {
            "Dispatch receiver for invoke cannot be safe: $dispatchReceiverForInvokeExtension"
        }
    }
}
