/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.profile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkAction;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.TransportSingleItemBulkWriteAction;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateAction;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.action.profile.Profile;
import org.elasticsearch.xpack.core.security.action.profile.SuggestProfilesRequest;
import org.elasticsearch.xpack.core.security.action.profile.SuggestProfilesResponse;
import org.elasticsearch.xpack.core.security.action.profile.UpdateProfileDataRequest;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.Subject;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.security.support.SecurityIndexManager;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.action.bulk.TransportSingleItemBulkWriteAction.toSingleItemBulkRequest;
import static org.elasticsearch.xpack.core.ClientHelper.SECURITY_PROFILE_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.core.security.authc.Authentication.isFileOrNativeRealm;
import static org.elasticsearch.xpack.security.support.SecuritySystemIndices.SECURITY_PROFILE_ALIAS;

public class ProfileService {
    private static final Logger logger = LogManager.getLogger(ProfileService.class);
    private static final String DOC_ID_PREFIX = "profile_";
    private static final BackoffPolicy DEFAULT_BACKOFF = BackoffPolicy.exponentialBackoff();
    private static final int DIFFERENTIATOR_UPPER_LIMIT = 9;

    private final Settings settings;
    private final Clock clock;
    private final Client client;
    private final SecurityIndexManager profileIndex;
    private final ThreadPool threadPool;

    public ProfileService(Settings settings, Clock clock, Client client, SecurityIndexManager profileIndex, ThreadPool threadPool) {
        this.settings = settings;
        this.clock = clock;
        this.client = client;
        this.profileIndex = profileIndex;
        this.threadPool = threadPool;
    }

    public void getProfile(String uid, @Nullable Set<String> dataKeys, ActionListener<Profile> listener) {
        getVersionedDocument(
            uid,
            listener.map(versionedDocument -> versionedDocument != null ? versionedDocument.toProfile(dataKeys) : null)
        );
    }

    // TODO: with request when we take request body for profile activation
    /**
     * Create a new profile or update an existing profile for the user of the given Authentication.
     * @param authentication This is the object from which the profile will be created or updated.
     *                       It contains information about the username and relevant realms and domain.
     *                       Note that this authentication object does not belong to the authenticating user
     *                       because the associated ActivateProfileRequest provides the authentication information
     *                       in the request body while the authenticating user is the one that has privileges
     *                       to submit the request.
     */
    public void activateProfile(Authentication authentication, ActionListener<Profile> listener) {
        final Subject subject = authentication.getEffectiveSubject();
        if (Subject.Type.USER != subject.getType()) {
            listener.onFailure(
                new IllegalArgumentException(
                    "profile is supported for user only, but subject is a [" + subject.getType().name().toLowerCase(Locale.ROOT) + "]"
                )
            );
            return;
        }

        if (User.isInternal(subject.getUser())) {
            listener.onFailure(
                new IllegalStateException("profile should not be created for internal user [" + subject.getUser().principal() + "]")
            );
            return;
        }

        searchVersionedDocumentForSubject(subject, ActionListener.wrap(versionedDocument -> {
            if (versionedDocument == null) {
                // The initial differentiator is 0 for new profile
                createNewProfile(subject, ProfileDocument.computeBaseUidForSubject(subject) + "_0", listener);
            } else {
                updateProfileForActivate(subject, versionedDocument, listener);

            }
        }, listener::onFailure));
    }

    public void updateProfileData(UpdateProfileDataRequest request, ActionListener<AcknowledgedResponse> listener) {
        final XContentBuilder builder;
        try {
            builder = XContentFactory.jsonBuilder();
            builder.startObject();
            {
                builder.field("user_profile");
                builder.startObject();
                {
                    if (false == request.getLabels().isEmpty()) {
                        builder.field("labels", request.getLabels());
                    }
                    if (false == request.getData().isEmpty()) {
                        builder.field("application_data", request.getData());
                    }
                }
                builder.endObject();
            }
            builder.endObject();
        } catch (IOException e) {
            listener.onFailure(e);
            return;
        }

        doUpdate(
            buildUpdateRequest(request.getUid(), builder, request.getRefreshPolicy(), request.getIfPrimaryTerm(), request.getIfSeqNo()),
            listener.map(updateResponse -> AcknowledgedResponse.TRUE)
        );
    }

    public void suggestProfile(SuggestProfilesRequest request, ActionListener<SuggestProfilesResponse> listener) {
        tryFreezeAndCheckIndex(listener.map(response -> {
            assert response == null : "only null response can reach here";
            return new SuggestProfilesResponse(
                new SuggestProfilesResponse.ProfileHit[] {},
                0,
                new TotalHits(0, TotalHits.Relation.EQUAL_TO)
            );
        })).ifPresent(frozenProfileIndex -> {
            final SearchRequest searchRequest = buildSearchRequest(request);

            frozenProfileIndex.checkIndexVersionThenExecute(
                listener::onFailure,
                () -> executeAsyncWithOrigin(
                    client,
                    SECURITY_PROFILE_ORIGIN,
                    SearchAction.INSTANCE,
                    searchRequest,
                    ActionListener.wrap(searchResponse -> {
                        final SearchHits searchHits = searchResponse.getHits();
                        final SearchHit[] hits = searchHits.getHits();
                        final SuggestProfilesResponse.ProfileHit[] profileHits;
                        if (hits.length == 0) {
                            profileHits = new SuggestProfilesResponse.ProfileHit[0];
                        } else {
                            profileHits = new SuggestProfilesResponse.ProfileHit[hits.length];
                            for (int i = 0; i < hits.length; i++) {
                                final SearchHit hit = hits[i];
                                final VersionedDocument versionedDocument = new VersionedDocument(
                                    buildProfileDocument(hit.getSourceRef()),
                                    hit.getPrimaryTerm(),
                                    hit.getSeqNo()
                                );
                                profileHits[i] = new SuggestProfilesResponse.ProfileHit(
                                    versionedDocument.toProfile(request.getDataKeys()),
                                    hit.getScore()
                                );
                            }
                        }
                        listener.onResponse(
                            new SuggestProfilesResponse(profileHits, searchResponse.getTook().millis(), searchHits.getTotalHits())
                        );
                    }, listener::onFailure)
                )
            );
        });
    }

    public void setEnabled(String uid, boolean enabled, RefreshPolicy refreshPolicy, ActionListener<AcknowledgedResponse> listener) {
        final XContentBuilder builder;
        try {
            builder = XContentFactory.jsonBuilder();
            builder.startObject().field("user_profile", Map.of("enabled", enabled)).endObject();
        } catch (IOException e) {
            listener.onFailure(e);
            return;
        }
        doUpdate(buildUpdateRequest(uid, builder, refreshPolicy, -1, -1), listener.map(updateResponse -> AcknowledgedResponse.TRUE));
    }

    // package private for testing
    SearchRequest buildSearchRequest(SuggestProfilesRequest request) {
        final BoolQueryBuilder query = QueryBuilders.boolQuery().filter(QueryBuilders.termQuery("user_profile.enabled", true));
        if (Strings.hasText(request.getName())) {
            query.must(
                QueryBuilders.multiMatchQuery(
                    request.getName(),
                    "user_profile.user.username",
                    "user_profile.user.username._2gram",
                    "user_profile.user.username._3gram",
                    "user_profile.user.full_name",
                    "user_profile.user.full_name._2gram",
                    "user_profile.user.full_name._3gram",
                    "user_profile.user.email"
                ).type(MultiMatchQueryBuilder.Type.BOOL_PREFIX).fuzziness(Fuzziness.AUTO)
            );
        }
        final SuggestProfilesRequest.Hint hint = request.getHint();
        if (hint != null) {
            final List<String> hintedUids = hint.getUids();
            if (hintedUids != null) {
                assert false == hintedUids.isEmpty() : "uids hint cannot be empty";
                query.should(QueryBuilders.termsQuery("user_profile.uid", hintedUids));
            }
            final Tuple<String, List<String>> label = hint.getSingleLabel();
            if (label != null) {
                final List<String> labelValues = label.v2();
                query.should(QueryBuilders.termsQuery("user_profile.labels." + label.v1(), labelValues));
            }
            query.minimumShouldMatch(0);
        }

        return client.prepareSearch(SECURITY_PROFILE_ALIAS)
            .setQuery(query)
            .setSize(request.getSize())
            .addSort("_score", SortOrder.DESC)
            .addSort("user_profile.last_synchronized", SortOrder.DESC)
            .request();
    }

    private void getVersionedDocument(String uid, ActionListener<VersionedDocument> listener) {
        tryFreezeAndCheckIndex(listener).ifPresent(frozenProfileIndex -> {
            final GetRequest getRequest = new GetRequest(SECURITY_PROFILE_ALIAS, uidToDocId(uid));
            frozenProfileIndex.checkIndexVersionThenExecute(
                listener::onFailure,
                () -> executeAsyncWithOrigin(
                    client,
                    SECURITY_PROFILE_ORIGIN,
                    GetAction.INSTANCE,
                    getRequest,
                    ActionListener.wrap(response -> {
                        if (false == response.isExists()) {
                            logger.debug("profile with uid [{}] does not exist", uid);
                            listener.onResponse(null);
                            return;
                        }
                        listener.onResponse(
                            new VersionedDocument(
                                buildProfileDocument(response.getSourceAsBytesRef()),
                                response.getPrimaryTerm(),
                                response.getSeqNo()
                            )
                        );
                    }, listener::onFailure)
                )
            );
        });
    }

    // Package private for testing
    void searchVersionedDocumentForSubject(Subject subject, ActionListener<VersionedDocument> listener) {
        tryFreezeAndCheckIndex(listener).ifPresent(frozenProfileIndex -> {
            final BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("user_profile.user.username.keyword", subject.getUser().principal()));
            if (subject.getRealm().getDomain() == null) {
                boolQuery.filter(QueryBuilders.termQuery("user_profile.user.realm.type", subject.getRealm().getType()));
                if (false == isFileOrNativeRealm(subject.getRealm().getType())) {
                    boolQuery.filter(QueryBuilders.termQuery("user_profile.user.realm.name", subject.getRealm().getName()));
                }
            } else {
                logger.debug(
                    () -> new ParameterizedMessage(
                        "searching existing profile document for user [{}] from any of the realms [{}] under domain [{}]",
                        subject.getUser().principal(),
                        Strings.collectionToCommaDelimitedString(subject.getRealm().getDomain().realms()),
                        subject.getRealm().getDomain().name()
                    )
                );
                subject.getRealm().getDomain().realms().forEach(realmIdentifier -> {
                    final BoolQueryBuilder perRealmQuery = QueryBuilders.boolQuery()
                        .filter(QueryBuilders.termQuery("user_profile.user.realm.type", realmIdentifier.getType()));
                    if (false == isFileOrNativeRealm(realmIdentifier.getType())) {
                        perRealmQuery.filter(QueryBuilders.termQuery("user_profile.user.realm.name", realmIdentifier.getName()));
                    }
                    boolQuery.should(perRealmQuery);
                });
                boolQuery.minimumShouldMatch(1);
            }

            final SearchRequest searchRequest = client.prepareSearch(SECURITY_PROFILE_ALIAS).setQuery(boolQuery).request();
            frozenProfileIndex.checkIndexVersionThenExecute(
                listener::onFailure,
                () -> executeAsyncWithOrigin(
                    client,
                    SECURITY_PROFILE_ORIGIN,
                    SearchAction.INSTANCE,
                    searchRequest,
                    ActionListener.wrap(searchResponse -> {
                        final SearchHits searchHits = searchResponse.getHits();
                        final SearchHit[] hits = searchHits.getHits();
                        if (hits.length < 1) {
                            logger.debug(
                                "profile does not exist for username [{}] and realm name [{}]",
                                subject.getUser().principal(),
                                subject.getRealm().getName()
                            );
                            listener.onResponse(null);
                        } else if (hits.length == 1) {
                            final SearchHit hit = hits[0];
                            final ProfileDocument profileDocument = buildProfileDocument(hit.getSourceRef());
                            if (subject.canAccessResourcesOf(profileDocument.subject())) {
                                listener.onResponse(new VersionedDocument(profileDocument, hit.getPrimaryTerm(), hit.getSeqNo()));
                            } else {
                                final ParameterizedMessage errorMessage = new ParameterizedMessage(
                                    "profile [{}] matches search criteria but is not accessible to "
                                        + "the current subject with username [{}] and realm name [{}]",
                                    profileDocument.uid(),
                                    subject.getUser().principal(),
                                    subject.getRealm().getName()
                                );
                                logger.error(errorMessage);
                                assert false : "this should not happen";
                                listener.onFailure(new ElasticsearchException(errorMessage.getFormattedMessage()));
                            }
                        } else {
                            final ParameterizedMessage errorMessage = new ParameterizedMessage(
                                "multiple [{}] profiles [{}] found for user [{}] from realm [{}]{}",
                                hits.length,
                                Arrays.stream(hits)
                                    .map(SearchHit::getId)
                                    .map(ProfileService::docIdToUid)
                                    .sorted()
                                    .collect(Collectors.joining(",")),
                                subject.getUser().principal(),
                                subject.getRealm().getName(),
                                subject.getRealm().getDomain() == null
                                    ? ""
                                    : (" under domain [" + subject.getRealm().getDomain().name() + "]")
                            );
                            logger.error(errorMessage);
                            listener.onFailure(new ElasticsearchException(errorMessage.getFormattedMessage()));
                        }
                    }, listener::onFailure)
                )
            );
        });
    }

    private void createNewProfile(Subject subject, String uid, ActionListener<Profile> listener) throws IOException {
        // When the code reaches here, we are sure no existing profile matches the subject's username and realm info
        // We go ahead to create the new profile document. If there is another concurrent creation request, it should
        // attempt to create a doc with the same ID and cause version conflict which is handled.
        final ProfileDocument profileDocument = ProfileDocument.fromSubjectWithUid(subject, uid);
        final String docId = uidToDocId(profileDocument.uid());
        final BulkRequest bulkRequest = toSingleItemBulkRequest(
            client.prepareIndex(SECURITY_PROFILE_ALIAS)
                .setId(docId)
                .setSource(wrapProfileDocument(profileDocument))
                .setOpType(DocWriteRequest.OpType.CREATE)
                .setRefreshPolicy(RefreshPolicy.WAIT_UNTIL)
                .request()
        );
        profileIndex.prepareIndexIfNeededThenExecute(
            listener::onFailure,
            () -> executeAsyncWithOrigin(
                client,
                SECURITY_PROFILE_ORIGIN,
                BulkAction.INSTANCE,
                bulkRequest,
                TransportSingleItemBulkWriteAction.<IndexResponse>wrapBulkResponse(ActionListener.wrap(indexResponse -> {
                    assert docId.equals(indexResponse.getId());
                    final VersionedDocument versionedDocument = new VersionedDocument(
                        profileDocument,
                        indexResponse.getPrimaryTerm(),
                        indexResponse.getSeqNo()
                    );
                    listener.onResponse(versionedDocument.toProfile(Set.of()));
                }, e -> {
                    if (e instanceof VersionConflictEngineException) {
                        // Document already exists with the specified ID, get the document with the ID
                        // and check whether it is the right profile for the subject
                        getOrCreateProfileWithBackoff(subject, profileDocument, DEFAULT_BACKOFF.iterator(), listener);
                    } else {
                        listener.onFailure(e);
                    }
                }))
            )
        );
    }

    // Package private for test
    void getOrCreateProfileWithBackoff(
        Subject subject,
        ProfileDocument profileDocument,
        Iterator<TimeValue> backoff,
        ActionListener<Profile> listener
    ) {
        getVersionedDocument(profileDocument.uid(), ActionListener.wrap(versionedDocument -> {
            if (versionedDocument == null) {
                // Document not found. This can happen if the GET request hits a replica that is still processing the document
                if (backoff.hasNext()) {
                    final TimeValue backoffTimeValue = backoff.next();
                    logger.debug("retrying get profile document [{}] after [{}] backoff", profileDocument.uid(), backoffTimeValue);
                    client.threadPool()
                        .schedule(
                            () -> getOrCreateProfileWithBackoff(subject, profileDocument, backoff, listener),
                            backoffTimeValue,
                            ThreadPool.Names.GENERIC
                        );
                } else {
                    // Retry has depleted. This can only happen when the document or the profile index itself gets deleted
                    // in between requests.
                    listener.onFailure(
                        new ElasticsearchException("failed to retrieving profile [{}] after all retries", profileDocument.uid())
                    );
                }
                return;
            }
            // Ownership check between the subject and the profile document
            if (subject.canAccessResourcesOf(versionedDocument.doc.subject())) {
                // The profile document can be accessed by the subject. It must have just got created by another thread, i.e. racing.
                // Still need to update it with current auth info before return.
                logger.debug(
                    "found existing profile document [{}] accessible to the current subject with username [{}] and realm name [{}]",
                    versionedDocument.doc.uid(),
                    subject.getUser().principal(),
                    subject.getRealm().getName()
                );
                updateProfileForActivate(subject, versionedDocument, listener);
            } else {
                // The profile document is NOT a match, this means either genuine hash collision or profile document
                // was manually updated, e.g. realm or user rename etc. So we attempt to differentiate from the
                // existing profile document by increase the differentiator number by 1.
                incrementDifferentiatorAndCreateNewProfile(subject, profileDocument, listener);
            }
        }, listener::onFailure));
    }

    // Package private for tests
    void incrementDifferentiatorAndCreateNewProfile(Subject subject, ProfileDocument profileDocument, ActionListener<Profile> listener)
        throws IOException {
        final String uid = profileDocument.uid();
        final int index = uid.lastIndexOf("_");
        if (index == -1) {
            listener.onFailure(new ElasticsearchException("profile uid [{}] does not contain any underscore character", uid));
            return;
        }
        final String baseUid = uid.substring(0, index);
        final String differentiatorString = uid.substring(index + 1);
        if (differentiatorString.isBlank()) {
            listener.onFailure(new ElasticsearchException("profile uid [{}] does not contain a differentiator", uid));
            return;
        }
        final int differentiator;
        try {
            differentiator = Integer.parseInt(differentiatorString);
        } catch (NumberFormatException e) {
            listener.onFailure(new ElasticsearchException("profile uid [{}] differentiator is not a number", e, uid));
            return;
        }
        // Prevent infinite recursion. It is practically impossible to get this many clashes
        if (differentiator >= DIFFERENTIATOR_UPPER_LIMIT) {
            listener.onFailure(
                new ElasticsearchException("profile differentiator value is too high for base Uid [{}]", uid.substring(0, index))
            );
            return;
        }
        // New uid by increment the differentiator by 1
        final String newUid = baseUid + "_" + (differentiator + 1);
        createNewProfile(subject, newUid, listener);
    }

    private void updateProfileForActivate(Subject subject, VersionedDocument versionedDocument, ActionListener<Profile> listener)
        throws IOException {
        final ProfileDocument profileDocument = updateWithSubject(versionedDocument.doc, subject);

        doUpdate(
            buildUpdateRequest(
                profileDocument.uid(),
                wrapProfileDocumentWithoutApplicationData(profileDocument),
                RefreshPolicy.WAIT_UNTIL,
                versionedDocument.primaryTerm,
                versionedDocument.seqNo
            ),
            listener.map(
                updateResponse -> new VersionedDocument(profileDocument, updateResponse.getPrimaryTerm(), updateResponse.getSeqNo())
                    .toProfile(Set.of())
            )
        );
    }

    private UpdateRequest buildUpdateRequest(
        String uid,
        XContentBuilder builder,
        RefreshPolicy refreshPolicy,
        long ifPrimaryTerm,
        long ifSeqNo
    ) {
        final String docId = uidToDocId(uid);
        final UpdateRequestBuilder updateRequestBuilder = client.prepareUpdate(SECURITY_PROFILE_ALIAS, docId)
            .setDoc(builder)
            .setRefreshPolicy(refreshPolicy);

        if (ifPrimaryTerm >= 0) {
            updateRequestBuilder.setIfPrimaryTerm(ifPrimaryTerm);
        }
        if (ifSeqNo >= 0) {
            updateRequestBuilder.setIfSeqNo(ifSeqNo);
        }
        return updateRequestBuilder.request();
    }

    // Package private for testing
    void doUpdate(UpdateRequest updateRequest, ActionListener<UpdateResponse> listener) {
        profileIndex.prepareIndexIfNeededThenExecute(
            listener::onFailure,
            () -> executeAsyncWithOrigin(
                client,
                SECURITY_PROFILE_ORIGIN,
                UpdateAction.INSTANCE,
                updateRequest,
                ActionListener.wrap(updateResponse -> {
                    assert updateResponse.getResult() == DocWriteResponse.Result.UPDATED
                        || updateResponse.getResult() == DocWriteResponse.Result.NOOP;
                    listener.onResponse(updateResponse);
                }, listener::onFailure)
            )
        );
    }

    private static String uidToDocId(String uid) {
        return DOC_ID_PREFIX + uid;
    }

    private static String docIdToUid(String docId) {
        if (docId == null || false == docId.startsWith(DOC_ID_PREFIX)) {
            throw new IllegalStateException("profile document ID [" + docId + "] has unexpected value");
        }
        return docId.substring(DOC_ID_PREFIX.length());
    }

    static ProfileDocument buildProfileDocument(BytesReference source) throws IOException {
        if (source == null) {
            throw new IllegalStateException("profile document did not have source but source should have been fetched");
        }
        try (XContentParser parser = XContentHelper.createParser(XContentParserConfiguration.EMPTY, source, XContentType.JSON)) {
            return ProfileDocument.fromXContent(parser);
        }
    }

    private static XContentBuilder wrapProfileDocument(ProfileDocument profileDocument) throws IOException {
        final XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.field("user_profile", profileDocument);
        builder.endObject();
        return builder;
    }

    private static XContentBuilder wrapProfileDocumentWithoutApplicationData(ProfileDocument profileDocument) throws IOException {
        final XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.field(
            "user_profile",
            profileDocument,
            // NOT including the labels and data in the update request so they will not be changed
            new ToXContent.MapParams(Map.of("include_labels", Boolean.FALSE.toString(), "include_data", Boolean.FALSE.toString()))
        );
        builder.endObject();
        return builder;
    }

    /**
     * Freeze the profile index check its availability and return it if everything is ok.
     * Otherwise it calls the listener with null and returns an empty Optional.
     */
    private <T> Optional<SecurityIndexManager> tryFreezeAndCheckIndex(ActionListener<T> listener) {
        final SecurityIndexManager frozenProfileIndex = profileIndex.freeze();
        if (false == frozenProfileIndex.indexExists()) {
            logger.debug("profile index does not exist");
            listener.onResponse(null);
            return Optional.empty();
        } else if (false == frozenProfileIndex.isAvailable()) {
            listener.onFailure(frozenProfileIndex.getUnavailableReason());
            return Optional.empty();
        }
        return Optional.of(frozenProfileIndex);
    }

    private static ProfileDocument updateWithSubject(ProfileDocument doc, Subject subject) {
        final User subjectUser = subject.getUser();
        return new ProfileDocument(
            doc.uid(),
            true,
            Instant.now().toEpochMilli(),
            new ProfileDocument.ProfileDocumentUser(
                subjectUser.principal(),
                Arrays.asList(subjectUser.roles()),
                subject.getRealm(),
                // Replace with incoming information even when they are null
                subjectUser.email(),
                subjectUser.fullName()
            ),
            doc.labels(),
            doc.applicationData()
        );
    }

    // Package private for testing
    record VersionedDocument(ProfileDocument doc, long primaryTerm, long seqNo) {

        /**
         * Convert the index document to the user-facing Profile by filtering through the application data
         */
        Profile toProfile(Set<String> dataKeys) {
            assert dataKeys != null : "data keys must not be null";
            final Map<String, Object> applicationData;
            if (dataKeys.isEmpty()) {
                applicationData = Map.of();
            } else {
                applicationData = XContentHelper.convertToMap(doc.applicationData(), false, XContentType.JSON, dataKeys, null).v2();
            }

            return new Profile(
                doc.uid(),
                doc.enabled(),
                doc.lastSynchronized(),
                doc.user().toProfileUser(),
                doc.labels(),
                applicationData,
                new Profile.VersionControl(primaryTerm, seqNo)
            );
        }
    }
}
