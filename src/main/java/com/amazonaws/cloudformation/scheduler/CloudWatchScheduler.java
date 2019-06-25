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
package com.amazonaws.cloudformation.scheduler;

import com.amazonaws.cloudformation.injection.CloudWatchEventsProvider;
import com.amazonaws.cloudformation.proxy.HandlerRequest;
import com.amazonaws.cloudformation.proxy.RequestContext;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.util.UUID;

import lombok.Data;

import org.json.JSONObject;

import software.amazon.awssdk.services.cloudwatchevents.CloudWatchEventsClient;
import software.amazon.awssdk.services.cloudwatchevents.model.DeleteRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.DescribeRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.RemoveTargetsRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.RuleState;
import software.amazon.awssdk.services.cloudwatchevents.model.Target;
import software.amazon.awssdk.utils.StringUtils;

@Data
public class CloudWatchScheduler {

    private final CloudWatchEventsProvider cloudWatchEventsProvider;

    private final LambdaLogger logger;

    private final CronHelper cronHelper;
    private CloudWatchEventsClient client;

    public CloudWatchScheduler(final CloudWatchEventsProvider cloudWatchEventsProvider,
                               final LambdaLogger logger) {
        this.cloudWatchEventsProvider = cloudWatchEventsProvider;
        this.logger = logger;
        this.cronHelper = new CronHelper();
    }

    /**
     * This .ctor provided for testing
     */
    public CloudWatchScheduler(final CloudWatchEventsProvider cloudWatchEventsProvider,
                               final LambdaLogger logger,
                               final CronHelper cronHelper) {
        this.cloudWatchEventsProvider = cloudWatchEventsProvider;
        this.logger = logger;
        this.cronHelper = cronHelper;
    }

    /**
     * On Lambda re-invoke we need to supply a new set of client credentials so this
     * function must be called whenever credentials are refreshed/changed in the
     * owning entity
     */
    public void refreshClient() {
        this.client = cloudWatchEventsProvider.get();
    }

    /**
     * Schedule a re-invocation of the executing handler no less than 1 minute from
     * now
     *
     * @param functionArn the ARN oft he Lambda function to be invoked
     * @param minutesFromNow the minimum minutes from now that the re-invocation
     *            will occur. CWE provides only minute-granularity
     * @param handlerRequest additional context which the handler can provide itself
     *            for re-invocation
     */
    public <ResourceT, CallbackT> void rescheduleAfterMinutes(final String functionArn,
                                                              final int minutesFromNow,
                                                              final HandlerRequest<ResourceT, CallbackT> handlerRequest) {
        assert client != null : "CloudWatchEventsClient was not initialised. You must call refreshClient() first.";

        // generate a cron expression; minutes must be a positive integer
        String cronRule = this.cronHelper.generateOneTimeCronExpression(Math.max(minutesFromNow, 1));

        UUID rescheduleId = UUID.randomUUID();
        String ruleName = String.format("reinvoke-handler-%s", rescheduleId);
        String targetId = String.format("reinvoke-target-%s", rescheduleId);

        // record the CloudWatchEvents objects for cleanup on the callback
        RequestContext<CallbackT> requestContext = handlerRequest.getRequestContext();
        requestContext.setCloudWatchEventsRuleName(ruleName);
        requestContext.setCloudWatchEventsTargetId(targetId);

        String jsonRequest = new JSONObject(handlerRequest).toString();
        this.log(String.format("Scheduling re-invoke at %s (%s)%n", cronRule, rescheduleId));

        PutRuleRequest putRuleRequest = PutRuleRequest.builder().name(ruleName).scheduleExpression(cronRule)
            .state(RuleState.ENABLED).build();
        this.client.putRule(putRuleRequest);

        Target target = Target.builder().arn(functionArn).id(targetId).input(jsonRequest).build();
        PutTargetsRequest putTargetsRequest = PutTargetsRequest.builder().targets(target).rule(putRuleRequest.name()).build();
        this.client.putTargets(putTargetsRequest);

        DescribeRuleRequest describeRuleRequest = DescribeRuleRequest.builder().name(ruleName).build();
        this.client.describeRule(describeRuleRequest);
    }

    /**
     * After a re-invocation, the CWE rule which generated the reinvocation should
     * be scrubbed
     *
     * @param cloudWatchEventsRuleName the name of the CWE rule which triggered a
     *            re-invocation
     * @param cloudWatchEventsTargetId the target of the CWE rule which triggered a
     *            re-invocation
     */
    public void cleanupCloudWatchEvents(final String cloudWatchEventsRuleName, final String cloudWatchEventsTargetId) {
        assert client != null : "CloudWatchEventsClient was not initialised. You must call refreshClient() first.";

        try {
            if (!StringUtils.isBlank(cloudWatchEventsTargetId)) {
                RemoveTargetsRequest removeTargetsRequest = RemoveTargetsRequest.builder().ids(cloudWatchEventsTargetId)
                    .rule(cloudWatchEventsRuleName).build();
                this.client.removeTargets(removeTargetsRequest);
            }
        } catch (final Throwable e) {
            this.log(String.format("Error cleaning CloudWatchEvents Target (targetId=%s): %s", cloudWatchEventsTargetId,
                e.getMessage()));
        }
        try {
            if (!StringUtils.isBlank(cloudWatchEventsRuleName)) {
                DeleteRuleRequest deleteRuleRequest = DeleteRuleRequest.builder().name(cloudWatchEventsRuleName).build();
                this.client.deleteRule(deleteRuleRequest);
            }
        } catch (final Throwable e) {
            this.log(
                String.format("Error cleaning CloudWatchEvents (ruleName=%s): %s", cloudWatchEventsRuleName, e.getMessage()));
        }
    }

    /**
     * null-safe logger redirect
     *
     * @param message A string containing the event to log.
     */
    private void log(final String message) {
        if (this.logger != null) {
            this.logger.log(message);
        }
    }
}
