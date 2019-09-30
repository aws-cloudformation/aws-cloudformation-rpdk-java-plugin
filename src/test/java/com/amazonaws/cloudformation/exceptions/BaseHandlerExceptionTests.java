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

import static junit.framework.Assert.*;

import com.amazonaws.cloudformation.proxy.HandlerErrorCode;

import org.junit.jupiter.api.Test;

public class BaseHandlerExceptionTests {
    private static final String EXPECTED_EXCEPTION_MESSAGE = "Resource of type 'AWS::Type::Resource' with identifier 'myId' encountered an error.";

    @Test
    public void resourceProviderException_isRuntimeException() {
        try {
            throw new BaseHandlerException("AWS::Type::Resource", "myId", new RuntimeException(),
                                           HandlerErrorCode.InternalFailure);
        } catch (final RuntimeException e) {
            assertEquals(e.getMessage(), EXPECTED_EXCEPTION_MESSAGE);
            assertNotNull(e.getCause());
        }
    }

    @Test
    public void resourceProviderException_singleArgConstructorHasNoMessage() {
        try {
            throw new BaseHandlerException(new RuntimeException(), HandlerErrorCode.InternalFailure);
        } catch (final BaseHandlerException e) {
            assertNull(e.getMessage());
            assertNotNull(e.getCause());
            assertEquals(e.getErrorCode(), HandlerErrorCode.InternalFailure);
        }
    }

    @Test
    public void resourceProviderException_noCauseGiven() {
        try {
            throw new BaseHandlerException("AWS::Type::Resource", "myId", HandlerErrorCode.InternalFailure);
        } catch (final BaseHandlerException e) {
            assertEquals(e.getMessage(), EXPECTED_EXCEPTION_MESSAGE);
            assertNull(e.getCause());
            assertEquals(e.getErrorCode(), HandlerErrorCode.InternalFailure);
        }
    }
}
