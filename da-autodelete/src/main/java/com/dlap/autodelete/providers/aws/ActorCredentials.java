/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.autodelete.providers.aws;

import java.net.URI;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * The AWS credentials of the delete executor's Actor role (EXEC-01, Q-722, ADR-0110).
 *
 * <p>Without {@code dlap.autodelete.aws.credentials-ref} the executor uses the SDK default chain, so the
 * executor host's own instance profile, environment keys, a shared profile or an EKS web identity token (IRSA)
 * is the Actor. With it, the value is an IAM role ARN (or the name of an environment variable that holds one,
 * optionally written {@code env:NAME}); the service assumes that role with STS, using the default chain as the
 * caller, session name {@value #ROLE_SESSION_NAME}, and the optional
 * {@code dlap.autodelete.aws.external-id} and {@code dlap.autodelete.aws.sts-endpoint}. This is
 * configuration only: nothing is called until the first request is signed, and the role ARN and external id are
 * never logged.
 *
 * <p>One provider is shared by every client the executor builds, so STS caches and refreshes the role's session
 * credentials in one place.
 */
@Component
public class ActorCredentials implements DisposableBean {

    /** The session name of an assumed role, visible in the audited account's CloudTrail. */
    public static final String ROLE_SESSION_NAME = "dlap-autodelete";

    private static final Logger LOG = LoggerFactory.getLogger(ActorCredentials.class);

    private static final Pattern ROLE_ARN = Pattern.compile(
            "arn:aws[a-z-]*:iam::\\d{12}:role/[A-Za-z0-9+=,.@_/-]{1,512}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final String roleArn;
    private final String externalId;
    private final String stsEndpoint;
    private final String stsRegion;
    private final Function<AwsCredentialsProvider, StsClient> stsFactory;

    private volatile AwsCredentialsProvider delegate;
    private volatile AwsCredentialsProvider shared;
    private volatile StsClient sts;

    /**
     * The credentials Spring builds from the settings.
     *
     * @param credentialsRef an IAM role ARN, or the name of a variable holding one; empty for the default chain
     * @param externalId     the {@code sts:ExternalId} the role's trust policy requires; empty for none
     * @param stsEndpoint    an STS endpoint URL (a VPC endpoint or an emulator); empty for AWS
     * @param stsRegion      the region of the STS call; empty to use the default region chain, else us-east-1
     * @throws IllegalStateException when the credentials-ref is not a role ARN and names no variable that holds one
     */
    @Autowired
    public ActorCredentials(@Value("${dlap.autodelete.aws.credentials-ref:}") String credentialsRef,
                            @Value("${dlap.autodelete.aws.external-id:}") String externalId,
                            @Value("${dlap.autodelete.aws.sts-endpoint:}") String stsEndpoint,
                            @Value("${dlap.autodelete.aws.sts-region:}") String stsRegion) {
        this(credentialsRef, externalId, stsEndpoint, stsRegion, System::getenv, null);
    }

    /**
     * Credentials with the environment and the STS client factory injected (tests).
     *
     * @param credentialsRef an IAM role ARN, or the name of a variable holding one; empty for the default chain
     * @param externalId     the external id; empty for none
     * @param stsEndpoint    an STS endpoint URL; empty for AWS
     * @param stsRegion      the STS region; empty for the default chain
     * @param env            the environment lookup, for a credentials-ref that names a variable
     * @param stsFactory     builds the STS client from the caller's credentials; null for the real client
     * @throws IllegalStateException when the credentials-ref is not a role ARN and names no variable that holds one
     */
    public ActorCredentials(String credentialsRef, String externalId, String stsEndpoint, String stsRegion,
                     UnaryOperator<String> env, Function<AwsCredentialsProvider, StsClient> stsFactory) {
        String ref = credentialsRef == null ? "" : credentialsRef.strip();
        this.roleArn = ref.isEmpty() ? null : roleArn(ref, env);
        this.externalId = externalId == null || externalId.isBlank() ? null : externalId.strip();
        this.stsEndpoint = stsEndpoint == null || stsEndpoint.isBlank() ? null : stsEndpoint.strip();
        this.stsRegion = stsRegion == null || stsRegion.isBlank() ? null : stsRegion.strip();
        this.stsFactory = stsFactory != null ? stsFactory : this::realStsClient;
        if (this.roleArn != null) {
            LOG.info("The delete executor assumes its Actor role with STS (session name {}); the role is configured "
                    + "in dlap.autodelete.aws.credentials-ref", ROLE_SESSION_NAME);
        }
    }

    /**
     * The default chain only: the executor host is the Actor. Used where no configuration exists (tests, the
     * no-argument constructors).
     *
     * @return credentials that never assume a role
     */
    public static ActorCredentials defaultChain() {
        return new ActorCredentials("", "", "", "");
    }

    /**
     * Whether the executor assumes a role with STS.
     *
     * @return true when a credentials-ref is configured
     */
    public boolean assumesRole() {
        return roleArn != null;
    }

    /**
     * The credentials provider every client of the executor uses: the role-assuming provider, or the default chain.
     * Built once; nothing is called until a request is signed. The executors build a client per call inside
     * try-with-resources, and the SDK closes a closeable provider together with its client, so the shared provider
     * is handed out behind a view that cannot be closed; {@link #destroy()} closes the real one.
     *
     * @return the provider, shared and not closed by the clients that use it
     */
    public AwsCredentialsProvider provider() {
        AwsCredentialsProvider p = shared;
        if (p == null) {
            synchronized (this) {
                p = shared;
                if (p == null) {
                    delegate = roleArn == null ? DefaultCredentialsProvider.create() : assumeRole();
                    p = new NonClosing(delegate);
                    shared = p;
                }
            }
        }
        return p;
    }

    /**
     * The real provider behind {@link #provider()}.
     *
     * @return the role-assuming provider, or the default chain
     */
    AwsCredentialsProvider delegate() {
        provider();
        return delegate;
    }

    private AwsCredentialsProvider assumeRole() {
        sts = stsFactory.apply(DefaultCredentialsProvider.create());
        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts)
                .refreshRequest(assumeRoleRequest())
                .build();
    }

    /**
     * The AssumeRole call this executor makes.
     *
     * @return the request: role, session name and the external id when configured
     * @throws IllegalStateException when no role is configured
     */
    AssumeRoleRequest assumeRoleRequest() {
        if (roleArn == null) {
            throw new IllegalStateException("no role is configured");
        }
        AssumeRoleRequest.Builder r = AssumeRoleRequest.builder().roleArn(roleArn).roleSessionName(ROLE_SESSION_NAME);
        if (externalId != null) {
            r.externalId(externalId);
        }
        return r.build();
    }

    private StsClient realStsClient(AwsCredentialsProvider caller) {
        StsClientBuilder b = StsClient.builder().credentialsProvider(caller).region(stsRegion());
        if (stsEndpoint != null) {
            b.endpointOverride(URI.create(stsEndpoint));
        }
        return b.build();
    }

    private Region stsRegion() {
        if (stsRegion != null) {
            return Region.of(stsRegion);
        }
        try {
            return DefaultAwsRegionProviderChain.builder().build().getRegion();
        } catch (RuntimeException e) {
            return Region.US_EAST_1;
        }
    }

    /**
     * The role a credentials-ref names.
     *
     * @param ref the reference: a role ARN, or {@code NAME} / {@code env:NAME} of a variable holding one
     * @param env the environment lookup
     * @return the role ARN
     * @throws IllegalStateException when the reference is not a role ARN and names no variable that holds one; the
     *                               message never holds the reference or the variable's value
     */
    static String roleArn(String ref, UnaryOperator<String> env) {
        if (ref.startsWith("arn:")) {
            if (!ROLE_ARN.matcher(ref).matches()) {
                throw new IllegalStateException("dlap.autodelete.aws.credentials-ref must be an IAM role ARN");
            }
            return ref;
        }
        String name = ref.startsWith("env:") ? ref.substring(4) : ref;
        if (!ENV_NAME.matcher(name).matches()) {
            throw new IllegalStateException("dlap.autodelete.aws.credentials-ref must be an IAM role ARN or the name "
                    + "of an environment variable that holds one");
        }
        String value = env.apply(name);
        if (value == null || !ROLE_ARN.matcher(value.strip()).matches()) {
            throw new IllegalStateException("environment variable " + name + " does not hold an IAM role ARN");
        }
        return value.strip();
    }

    @Override
    public void destroy() {
        AwsCredentialsProvider p = delegate;
        if (p instanceof StsAssumeRoleCredentialsProvider role) {
            closeQuietly(role::close);
        }
        StsClient s = sts;
        if (s != null) {
            closeQuietly(s::close);
        }
    }

    private static void closeQuietly(Runnable close) {
        try {
            close.run();
        } catch (RuntimeException e) {
            LOG.debug("Closing the credentials provider failed: {}", e.getClass().getSimpleName());
        }
    }

    /** A view of a provider that is not {@code AutoCloseable}, so a client closing does not close the provider. */
    private static final class NonClosing implements AwsCredentialsProvider {
        private final AwsCredentialsProvider target;

        NonClosing(AwsCredentialsProvider target) {
            this.target = target;
        }

        @Override
        public AwsCredentials resolveCredentials() {
            return target.resolveCredentials();
        }
    }
}
