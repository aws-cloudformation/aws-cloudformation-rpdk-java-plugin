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
package software.amazon.cloudformation.injection;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.cloudformation.proxy.Credentials;

public interface CredentialsProvider {

    /**
     * @return the current set of credentials for initialising AWS SDK Clients
     */
    AwsSessionCredentials get();

    /**
     * Inject a new set of credentials (passed through from caller)
     *
     * @param credentials, incoming credentials for the call that is being made
     */
    void setCredentials(Credentials credentials);

    /**
     * set credentials back to null to avoid reusing stale creds
     */
    void resetCredentials();
}
