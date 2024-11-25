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
package software.amazon.cloudformation.proxy.hook.targetmodel;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChangedResource {
    @JsonProperty("LogicalResourceId")
    private String logicalResourceId;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("LineNumber")
    private Integer lineNumber;

    @JsonProperty("Action")
    private String action;

    @JsonProperty("ResourceProperties")
    private String resourceProperties;

    @JsonProperty("PreviousResourceProperties")
    private String previousResourceProperties;
}
