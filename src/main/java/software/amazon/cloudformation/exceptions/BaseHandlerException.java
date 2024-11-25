/*
* Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/
package software.amazon.cloudformation.exceptions;

import lombok.Getter;
import software.amazon.cloudformation.proxy.HandlerErrorCode;

@Getter
public abstract class BaseHandlerException extends RuntimeException {

    private static final long serialVersionUID = -1646136434112354328L;

    private final HandlerErrorCode errorCode;

    protected BaseHandlerException(final Throwable cause,
                                   final HandlerErrorCode errorCode) {
        super(cause.getMessage(), cause);
        this.errorCode = errorCode;
    }

    protected BaseHandlerException(final String message,
                                   final Throwable cause,
                                   final HandlerErrorCode errorCode) {
        super(String.format("%s %s", message, cause.getMessage()), cause);
        this.errorCode = errorCode;
    }

    protected BaseHandlerException(final String message,
                                   final HandlerErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
