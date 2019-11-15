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
package software.amazon.cloudformation;

import java.util.List;

import lombok.Data;

import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.OperationStatus;

@Data
public class Response<ResourceT> {
    /**
     * The bearerToken is used to report progress back to CloudFormation and is
     * passed back to CloudFormation
     */
    private String bearerToken;

    /**
     * The errorCode is used to report granular failures back to CloudFormation
     */
    private HandlerErrorCode errorCode;

    /**
     * The operationStatus indicates whether the handler has reached a terminal
     * state or is still computing and requires more time to complete
     */
    private OperationStatus operationStatus;

    /**
     * The handler can (and should) specify a contextual information message which
     * can be shown to callers to indicate the nature of a progress transition or
     * callback delay; for example a message indicating "propagating to edge"
     */
    private String message;

    /**
     * The output resource instance populated by a READ/LIST for synchronous results
     * and by CREATE/UPDATE/DELETE for final response validation/confirmation
     */
    private ResourceT resourceModel;

    /**
     * The output resource instances populated by a LIST for synchronous results
     */
    private List<ResourceT> resourceModels;

    /**
     * The token used to request additional pages of resources for a LIST operation
     */
    private String nextToken;
}
