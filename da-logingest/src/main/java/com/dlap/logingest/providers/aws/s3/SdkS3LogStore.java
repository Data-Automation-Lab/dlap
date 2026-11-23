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

package com.dlap.logingest.providers.aws.s3;

import com.dlap.logingest.transport.StreamContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Lists and reads log objects with the AWS SDK S3 client, one client per stream and client settings.
 *
 * <p>Every setting comes from the stream's configuration, none from code:
 * <ul>
 *   <li><b>Region</b> of the bucket: location key {@value #REGION}, else the binding's single scope
 *       region, else the SDK's default region chain ({@code AWS_REGION}, the profile, instance
 *       metadata).</li>
 *   <li><b>Endpoint</b>: location key {@value #ENDPOINT} (a VPC endpoint, an S3-compatible store, an
 *       emulator), with {@value #PATH_STYLE}{@code : true} for path-style addressing; else the SDK's
 *       regional endpoint.</li>
 *   <li><b>Credentials</b>: the SDK's default chain (environment, Java system properties, web
 *       identity such as EKS IRSA, the shared files' default profile, container and instance
 *       roles), or the named profile of the shared files with location key {@value #PROFILE}. A
 *       stream's {@code credentials-ref} that is an IAM role ARN (or {@code env:NAME} / {@code NAME}
 *       naming a variable that holds one) makes the service assume that role with STS, using the
 *       default chain or the named profile as the caller; the optional location key
 *       {@value #EXTERNAL_ID} is sent as the external id and {@value #STS_ENDPOINT} overrides the STS
 *       endpoint. The SDK refreshes the role's temporary credentials. Nothing is hardcoded and no
 *       secret is read from the configuration.</li>
 * </ul>
 * Credentials are never logged. Reads name the listed etag ({@code If-Match}), so an object
 * rewritten between listing and reading fails the read and is listed again on the next poll.
 */
public final class SdkS3LogStore implements S3LogStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SdkS3LogStore.class);

    /** Location key: the bucket's region. */
    public static final String REGION = "region";

    /** Location key: an endpoint URL. */
    public static final String ENDPOINT = "endpoint";

    /** Location key: {@code true} for path-style addressing. */
    public static final String PATH_STYLE = "path-style";

    /** Location key: a profile of the shared credentials and config files. */
    public static final String PROFILE = "profile";

    /** Location key: the external id sent when a role is assumed. */
    public static final String EXTERNAL_ID = "external-id";

    /** Location key: an STS endpoint URL (a VPC endpoint, an emulator). */
    public static final String STS_ENDPOINT = "sts-endpoint";

    /** The session name of an assumed role, visible in the audited account's CloudTrail. */
    static final String ROLE_SESSION_NAME = "dlap-logingest";

    private static final Pattern ROLE_ARN = Pattern.compile(
            "arn:aws[a-z-]*:iam::\\d{12}:role/[A-Za-z0-9+=,.@_/-]{1,512}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final Function<StreamContext, S3Client> factory;
    private final Map<String, S3Client> clients = new ConcurrentHashMap<>();
    private final List<AutoCloseable> owned = new CopyOnWriteArrayList<>();

    /**
     * The store that builds clients from each stream's settings.
     */
    public SdkS3LogStore() {
        this.factory = context -> client(context, owned);
    }

    /**
     * The store with a client factory, for tests.
     *
     * @param factory builds the client of a stream
     */
    SdkS3LogStore(Function<StreamContext, S3Client> factory) {
        this.factory = factory;
    }

    @Override
    public List<S3LogObject> list(StreamContext context, String bucket, String prefix, String startAfter,
                                  String stopAfter) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
        if (startAfter != null && !startAfter.isEmpty()) {
            request.startAfter(startAfter);
        }
        boolean bounded = stopAfter != null && !stopAfter.isEmpty();
        List<S3LogObject> out = new ArrayList<>();
        for (ListObjectsV2Response page : cached(context).listObjectsV2Paginator(request.build())) {
            for (S3Object o : page.contents()) {
                if (bounded && o.key().compareTo(stopAfter) > 0) {
                    return out;
                }
                out.add(new S3LogObject(o.key(), o.eTag(), o.size() == null ? 0 : o.size(), o.lastModified()));
            }
        }
        return out;
    }

    @Override
    public List<String> folders(StreamContext context, String bucket, String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).delimiter("/")
                .build();
        List<String> out = new ArrayList<>();
        for (ListObjectsV2Response page : cached(context).listObjectsV2Paginator(request)) {
            for (CommonPrefix p : page.commonPrefixes()) {
                out.add(p.prefix());
            }
        }
        return out;
    }

    @Override
    public byte[] read(StreamContext context, String bucket, S3LogObject object, long maxBytes) {
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(object.key());
        if (!object.etag().isEmpty()) {
            request.ifMatch("\"" + object.etag() + "\"");
        }
        if (object.size() > maxBytes) {
            request.range("bytes=0-" + (maxBytes - 1));
        }
        return cached(context).getObjectAsBytes(request.build()).asByteArray();
    }

    @Override
    public void destroy() {
        clients.values().forEach(S3Client::close);
        clients.clear();
        for (AutoCloseable c : owned) {
            try {
                c.close();
            } catch (Exception e) {
                // Closing a credentials provider or an STS client must not stop the shutdown.
                log.debug("Closing an S3 credentials resource failed: {}", e.getClass().getSimpleName());
            }
        }
        owned.clear();
    }

    private S3Client cached(StreamContext context) {
        return clients.computeIfAbsent(clientKey(context), k -> factory.apply(context));
    }

    /**
     * The cache key of a stream's client: everything the client is built from.
     *
     * @param context the stream
     * @return the key
     */
    static String clientKey(StreamContext context) {
        Map<String, String> l = context.stream().location();
        return String.join("|", context.stream().bindingId(), context.stream().streamId(),
                String.valueOf(l.get(REGION)), String.valueOf(l.get(ENDPOINT)), String.valueOf(l.get(PATH_STYLE)),
                String.valueOf(l.get(PROFILE)), String.valueOf(context.stream().credentialsRef()),
                String.valueOf(l.get(EXTERNAL_ID)), String.valueOf(l.get(STS_ENDPOINT)));
    }

    /**
     * Builds the client of a stream from its settings. A client built with a role ARN holds an STS
     * client and a credentials provider; use {@link #client(StreamContext, List)} to own them.
     *
     * @param context the stream
     * @return the client
     * @throws IllegalStateException when the credentials-ref is not a role ARN, or no region resolves
     */
    static S3Client client(StreamContext context) {
        return client(context, new ArrayList<>());
    }

    /**
     * Builds the client of a stream from its settings.
     *
     * @param context the stream
     * @param owned   receives the STS client and the credentials provider the client uses, so the
     *                caller can close them with the client
     * @return the client
     * @throws IllegalStateException when the credentials-ref is not a role ARN, or no region resolves
     */
    static S3Client client(StreamContext context, List<AutoCloseable> owned) {
        S3ClientBuilder b = S3Client.builder()
                .credentialsProvider(credentials(context, System::getenv, SdkS3LogStore::stsClient, owned))
                .region(region(context));
        String endpoint = AbstractS3LogTransport.location(context, ENDPOINT);
        if (endpoint != null) {
            b.endpointOverride(URI.create(endpoint));
        }
        String pathStyle = AbstractS3LogTransport.location(context, PATH_STYLE);
        if (pathStyle != null && "true".equals(pathStyle.toLowerCase(Locale.ROOT))) {
            b.forcePathStyle(true);
        }
        return b.build();
    }

    /**
     * The credentials of a stream: the default chain or a named profile, or the role its
     * credentials-ref names, assumed with STS. Nothing is called until the first request is signed.
     *
     * @param context the stream
     * @return the provider
     * @throws IllegalStateException when the credentials-ref is not a role ARN
     */
    static AwsCredentialsProvider credentials(StreamContext context) {
        return credentials(context, System::getenv, SdkS3LogStore::stsClient, new ArrayList<>());
    }

    /**
     * The credentials of a stream, with the environment and the STS client factory injected.
     *
     * @param context    the stream
     * @param env        the environment lookup, for a credentials-ref that names a variable
     * @param stsFactory builds the STS client from the stream and the caller's credentials
     * @param owned      receives what the provider needs closed
     * @return the provider
     * @throws IllegalStateException when the credentials-ref is not a role ARN; the message never
     *                               holds the reference or the variable's value
     */
    static AwsCredentialsProvider credentials(StreamContext context, UnaryOperator<String> env,
                                              BiFunction<StreamContext, AwsCredentialsProvider, StsClient> stsFactory,
                                              List<AutoCloseable> owned) {
        String profile = AbstractS3LogTransport.location(context, PROFILE);
        AwsCredentialsProvider caller = profile != null
                ? ProfileCredentialsProvider.builder().profileName(profile).build()
                : DefaultCredentialsProvider.builder().build();
        String ref = context.stream().credentialsRef();
        if (ref == null || ref.isBlank()) {
            return caller;
        }
        String roleArn = roleArn(ref.strip(), env);
        StsClient sts = stsFactory.apply(context, caller);
        StsAssumeRoleCredentialsProvider role = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts)
                .refreshRequest(assumeRoleRequest(context, roleArn))
                .build();
        owned.add(role::close);
        owned.add(sts::close);
        return role;
    }

    /**
     * The role a credentials-ref names.
     *
     * @param ref the reference: a role ARN, or {@code NAME} / {@code env:NAME} of a variable holding one
     * @param env the environment lookup
     * @return the role ARN
     * @throws IllegalStateException when the reference is not a role ARN and names no variable that holds one
     */
    static String roleArn(String ref, UnaryOperator<String> env) {
        if (ref.startsWith("arn:")) {
            if (!ROLE_ARN.matcher(ref).matches()) {
                throw new IllegalStateException("an S3 stream's credentials-ref must be an IAM role ARN");
            }
            return ref;
        }
        String name = ref.startsWith("env:") ? ref.substring(4) : ref;
        if (!ENV_NAME.matcher(name).matches()) {
            throw new IllegalStateException("an S3 stream's credentials-ref must be an IAM role ARN or the name of "
                    + "an environment variable that holds one");
        }
        String value = env.apply(name);
        if (value == null || !ROLE_ARN.matcher(value.strip()).matches()) {
            throw new IllegalStateException("environment variable " + name + " does not hold an IAM role ARN");
        }
        return value.strip();
    }

    /**
     * The AssumeRole call a stream makes.
     *
     * @param context the stream, for the optional external id
     * @param roleArn the role
     * @return the request
     */
    static AssumeRoleRequest assumeRoleRequest(StreamContext context, String roleArn) {
        AssumeRoleRequest.Builder r = AssumeRoleRequest.builder().roleArn(roleArn).roleSessionName(ROLE_SESSION_NAME);
        String externalId = AbstractS3LogTransport.location(context, EXTERNAL_ID);
        if (externalId != null) {
            r.externalId(externalId);
        }
        return r.build();
    }

    private static StsClient stsClient(StreamContext context, AwsCredentialsProvider caller) {
        StsClientBuilder b = StsClient.builder().credentialsProvider(caller).region(region(context));
        String endpoint = AbstractS3LogTransport.location(context, STS_ENDPOINT);
        if (endpoint != null) {
            b.endpointOverride(URI.create(endpoint));
        }
        return b.build();
    }

    /**
     * The bucket region of a stream.
     *
     * @param context the stream
     * @return the region
     * @throws IllegalStateException when none is configured or found
     */
    static Region region(StreamContext context) {
        String region = AbstractS3LogTransport.location(context, REGION);
        if (region == null) {
            region = context.stream().parse().assetIdHints().region();
        }
        if (region != null && !region.isBlank()) {
            return Region.of(region.strip());
        }
        try {
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkClientException e) {
            throw new IllegalStateException("no AWS region for the S3 log bucket: set the location key " + REGION
                    + " or AWS_REGION", e);
        }
    }
}
