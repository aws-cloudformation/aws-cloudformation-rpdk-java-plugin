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
package com.amazonaws.cloudformation.loggers;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

public class LambdaLogPublisherImpl extends LogPublisher {

    private final LambdaLogger logger;

    public LambdaLogPublisherImpl(final LambdaLogger logger) {
        // Make LambdaLogger have higher than default priority.
        this.logger = logger;
    }

    @Override
    public boolean publishLogEvent(String message) {
        this.logger.log(filterMessage(message));
        return true;
    }
}