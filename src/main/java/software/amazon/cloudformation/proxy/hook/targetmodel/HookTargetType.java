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

public enum HookTargetType {
    /**
     * An agnostic target typed used when we do not know the type of a Hook's
     * target. Typically, Hooks can support multiple target types which won't be
     * known until runtime.
     */
    GENERIC,

    /**
     * A target model meant to represent a target for a Resource Hook. This model
     * type will have properties specific to the resource type.
     */
    RESOURCE,

    /**
     * A target model meant to represent a target for a Stack Hook. This model type
     * will have properties specific to the stack type.
     */
    STACK,

    /**
     * A target model meant to represent a target for a stack Change Set Hook. This
     * model type will have properties specific to the change set type.
     */
    CHANGE_SET;
}
