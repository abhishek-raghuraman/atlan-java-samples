/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2023 Atlan Pte. Ltd. */
package com.atlan.samples.loaders.models;

import com.atlan.model.assets.*;
import com.atlan.model.enums.AtlanConnectorType;
import com.atlan.samples.loaders.AssetBatch;
import java.util.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for capturing the full details provided about a table.
 */
@Slf4j
@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class BucketDetails extends AssetDetails {
    public static final String COL_BUCKET_NAME = "BUCKET NAME";
    public static final String COL_BUCKET_ARN = "BUCKET ARN";

    private static final List<String> REQUIRED =
            List.of(ConnectionDetails.COL_CONNECTOR, ConnectionDetails.COL_CONNECTION, COL_BUCKET_NAME);
    private static final List<String> REQUIRED_EMPTY = List.of(ObjectDetails.COL_OBJECT_NAME);

    @ToString.Include
    private String connectionQualifiedName;

    @ToString.Include
    private String accountName;

    @ToString.Include
    private String name;

    @ToString.Include
    private String arn;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getIdentity() {
        return connectionQualifiedName + "/" + (accountName == null ? "" : accountName) + "/" + name;
    }

    /**
     * Construct a bucket's qualifiedName from the row of data and cache of connections.
     *
     * @param connectionCache cache of connections
     * @param row of data
     * @return the qualifiedName for the bucket on that row of data
     */
    public static String getQualifiedName(Map<ConnectionDetails, String> connectionCache, Map<String, String> row) {
        String connectionQualifiedName = ConnectionDetails.getQualifiedName(connectionCache, row);
        AtlanConnectorType type = Connection.getConnectorTypeFromQualifiedName(connectionQualifiedName);
        String bucketName = row.get(COL_BUCKET_NAME);
        switch (type) {
            case S3:
                String bucketARN = row.get(COL_BUCKET_ARN);
                if (bucketARN != null && bucketARN.length() > 0 && bucketName != null && bucketName.length() > 0) {
                    return S3.generateQualifiedName(connectionQualifiedName, bucketARN);
                }
                break;
            case GCS:
                if (bucketName != null && bucketName.length() > 0) {
                    return connectionQualifiedName + "/" + bucketName;
                }
                break;
            case ADLS:
                String accountQN = AccountDetails.getQualifiedName(connectionCache, row);
                if (accountQN != null && accountQN.length() > 0 && bucketName != null && bucketName.length() > 0) {
                    return accountQN + "/" + bucketName;
                }
                break;
            default:
                log.error("Unknown connector type for object stores: {}", type);
                break;
        }
        return null;
    }

    /**
     * Build up details about the bucket on the provided row.
     *
     * @param connectionCache a cache of connections that have first been resolved across the spreadsheet
     * @param row a row of data from the spreadsheet, as a map from column name to value
     * @param delim delimiter used in cells that can contain multiple values
     * @return the bucket details for that row
     */
    public static BucketDetails getFromRow(
            Map<ConnectionDetails, String> connectionCache, Map<String, String> row, String delim) {
        if (getMissingFields(row, REQUIRED).isEmpty()) {
            String connectionQualifiedName = ConnectionDetails.getQualifiedName(connectionCache, row);
            if (getRequiredEmptyFields(row, REQUIRED_EMPTY).isEmpty()) {
                return getFromRow(BucketDetails.builder(), row, delim)
                        .connectionQualifiedName(connectionQualifiedName)
                        .accountName(row.get(AccountDetails.COL_ACCOUNT))
                        .name(row.get(COL_BUCKET_NAME))
                        .arn(row.get(COL_BUCKET_ARN))
                        .build();
            } else {
                return BucketDetails.builder()
                        .connectionQualifiedName(connectionQualifiedName)
                        .accountName(row.get(AccountDetails.COL_ACCOUNT))
                        .name(row.get(COL_BUCKET_NAME))
                        .arn(row.get(COL_BUCKET_ARN))
                        .build();
            }
        }
        return null;
    }

    /**
     * Create buckets in bulk, if they do not exist, or update them if they do (idempotent).
     *
     * @param buckets the set of buckets to ensure exist
     * @param batchSize maximum number of buckets to create per batch
     */
    public static void upsert(Map<String, BucketDetails> buckets, int batchSize) {
        AssetBatch batch = new AssetBatch("bucket", batchSize);
        Map<String, List<String>> toClassifyS3 = new HashMap<>();
        Map<String, List<String>> toClassifyGCS = new HashMap<>();
        Map<String, List<String>> toClassifyADLS = new HashMap<>();

        for (BucketDetails details : buckets.values()) {
            String connectionQualifiedName = details.getConnectionQualifiedName();
            String accountName = details.getAccountName();
            String bucketName = details.getName();
            String bucketARN = details.getArn();
            AtlanConnectorType bucketType = Connection.getConnectorTypeFromQualifiedName(connectionQualifiedName);
            switch (bucketType) {
                case S3:
                    if (bucketARN != null && bucketARN.length() > 0) {
                        S3Bucket s3 = S3Bucket.creator(bucketName, connectionQualifiedName, bucketARN)
                                .description(details.getDescription())
                                .certificateStatus(details.getCertificate())
                                .certificateStatusMessage(details.getCertificateStatusMessage())
                                .announcementType(details.getAnnouncementType())
                                .announcementTitle(details.getAnnouncementTitle())
                                .announcementMessage(details.getAnnouncementMessage())
                                .ownerUsers(details.getOwnerUsers())
                                .ownerGroups(details.getOwnerGroups())
                                .build();
                        if (!details.getClassifications().isEmpty()) {
                            toClassifyS3.put(s3.getQualifiedName(), details.getClassifications());
                        }
                        batch.add(s3);
                    } else {
                        log.error("Unable to create an S3 bucket without an ARN: {}", details);
                    }
                    break;
                case GCS:
                    GCSBucket gcs = GCSBucket.creator(bucketName, connectionQualifiedName)
                            .description(details.getDescription())
                            .certificateStatus(details.getCertificate())
                            .certificateStatusMessage(details.getCertificateStatusMessage())
                            .announcementType(details.getAnnouncementType())
                            .announcementTitle(details.getAnnouncementTitle())
                            .announcementMessage(details.getAnnouncementMessage())
                            .ownerUsers(details.getOwnerUsers())
                            .ownerGroups(details.getOwnerGroups())
                            .build();
                    if (!details.getClassifications().isEmpty()) {
                        toClassifyGCS.put(gcs.getQualifiedName(), details.getClassifications());
                    }
                    batch.add(gcs);
                    break;
                case ADLS:
                    if (accountName != null && accountName.length() > 0) {
                        String accountQN = connectionQualifiedName + "/" + accountName;
                        ADLSContainer adls = ADLSContainer.creator(bucketName, accountQN)
                                .description(details.getDescription())
                                .certificateStatus(details.getCertificate())
                                .certificateStatusMessage(details.getCertificateStatusMessage())
                                .announcementType(details.getAnnouncementType())
                                .announcementTitle(details.getAnnouncementTitle())
                                .announcementMessage(details.getAnnouncementMessage())
                                .ownerUsers(details.getOwnerUsers())
                                .ownerGroups(details.getOwnerGroups())
                                .build();
                        if (!details.getClassifications().isEmpty()) {
                            toClassifyADLS.put(adls.getQualifiedName(), details.getClassifications());
                        }
                        batch.add(adls);
                    } else {
                        log.error("Unable to create an ADLS container without an account: {}", details);
                    }
                    break;
                default:
                    log.error("Invalid bucket type ({}) — skipping: {}", bucketType, details);
                    break;
            }
        }
        // And don't forget to flush out any that remain
        batch.flush();

        // Classifications must be added in a second pass, after the asset exists
        appendClassifications(toClassifyS3, S3Bucket.TYPE_NAME);
        appendClassifications(toClassifyGCS, GCSBucket.TYPE_NAME);
        appendClassifications(toClassifyADLS, ADLSContainer.TYPE_NAME);
    }
}
