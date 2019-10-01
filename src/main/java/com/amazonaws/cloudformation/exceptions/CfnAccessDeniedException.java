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
package com.amazonaws.cloudformation.exceptions;

import com.amazonaws.cloudformation.proxy.HandlerErrorCode;

public class CfnAccessDeniedException extends BaseHandlerException {

    private static final long serialVersionUID = -1646136434112354328L;
    private static final HandlerErrorCode ERROR_CODE = HandlerErrorCode.AccessDenied;

    public CfnAccessDeniedException(final Throwable cause) {
        super(cause, ERROR_CODE);
    }

    public CfnAccessDeniedException(final String operation) {
        this(operation, null);
    }

    public CfnAccessDeniedException(final String operation,
                                    final Throwable cause) {
        super(String.format(ERROR_CODE.getMessage(), operation), cause, ERROR_CODE);
    }
}
