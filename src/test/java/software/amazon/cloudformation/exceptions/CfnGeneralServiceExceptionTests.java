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

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.Assert.assertEquals;
import org.junit.jupiter.api.Test;
import software.amazon.cloudformation.proxy.HandlerErrorCode;

public class CfnGeneralServiceExceptionTests {
    @Test
    public void cfnGeneralServiceException_isBaseHandlerException() {
        assertThatExceptionOfType(BaseHandlerException.class).isThrownBy(() -> {
            throw new CfnGeneralServiceException("CreateThing", new RuntimeException());
        }).withCauseInstanceOf(RuntimeException.class).withMessageContaining("CreateThing").withMessageNotContaining("internal")
            .withMessageContaining("Error occurred");
    }

    @Test
    public void cfnGeneralServiceException_singleArgConstructorHasNoMessage() {
        assertThatExceptionOfType(CfnGeneralServiceException.class).isThrownBy(() -> {
            throw new CfnGeneralServiceException(new RuntimeException());
        }).withCauseInstanceOf(RuntimeException.class).withMessage(null);
    }

    @Test
    public void cfnGeneralServiceException_noCauseGiven() {
        assertThatExceptionOfType(CfnGeneralServiceException.class).isThrownBy(() -> {
            throw new CfnGeneralServiceException("CreateThing");
        }).withNoCause().withMessageContaining("CreateThing").withMessageNotContaining("internal")
            .withMessageContaining("Error occurred");
    }

    @Test
    public void cfnGeneralServiceException_errorCodeIsAppropriate() {
        assertThatExceptionOfType(CfnGeneralServiceException.class).isThrownBy(() -> {
            throw new CfnGeneralServiceException(new RuntimeException());
        }).satisfies(exception -> assertEquals(HandlerErrorCode.GeneralServiceException, exception.getErrorCode()));
    }

    @Test
    public void cfnGeneralServiceException_errorMessage() {
        assertThatExceptionOfType(CfnGeneralServiceException.class).isThrownBy(() -> {
            throw new CfnGeneralServiceException(new RuntimeException("something wrong"));
        }).satisfies(exception -> assertEquals("something wrong", exception.getMessage()));
    }

    @Test
    public void cfnGeneralServiceException_detailedErrorMessage() {
        assertThatExceptionOfType(CfnGeneralServiceException.class).isThrownBy(() -> {
            throw new CfnGeneralServiceException("Some operation", new RuntimeException("something wrong"));
        }).satisfies(exception -> assertEquals("Error occurred during operation 'Some operation'. something wrong", exception.getMessage()));
    }
}
