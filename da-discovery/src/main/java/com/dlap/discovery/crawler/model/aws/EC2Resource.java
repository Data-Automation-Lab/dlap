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

package com.dlap.discovery.crawler.model.aws;

import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
// import jakarta.persistence.DiscriminatorValue; // Ensure this import is removed or commented IF NOT USED by other annotations
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Represents an AWS EC2 Instance resource.
 */
@Entity
@Table(name = "ec2_resource")
// @DiscriminatorValue("aws_ec2_instance") // Ensure this line is REMOVED
public class EC2Resource extends AwsResource {

    /** Type id the crawler's own constant ({@code AWSConstants.RESOURCE_TYPE_EC2_INSTANCE}) carries. */
    public static final String TYPE_ID = "aws_ec2_instance";

    /**
     * Type id the cloud-hierarchy configuration (application.yml) declares for an EC2 instance.
     * The two ids name the same type; code that matches EC2 resources accepts both.
     */
    public static final String HIERARCHY_TYPE_ID = "EC2_INSTANCE";

    /**
     * Whether a resource type id names an EC2 instance.
     *
     * @param typeId a resource type id; may be {@code null}
     * @return true for {@value #TYPE_ID} and {@value #HIERARCHY_TYPE_ID}
     */
    public static boolean isEc2TypeId(String typeId) {
        return TYPE_ID.equals(typeId) || HIERARCHY_TYPE_ID.equals(typeId);
    }

    /** EC2 instance type (e.g., t2.micro, m5.large). */
    @Column(name = "ec2_instance_type")
    private String instanceType;

    /** AMI ID used to launch the instance. */
    @Column(name = "ec2_image_id")
    private String imageId;

    /** Primary private IPv4 address. */
    @Column(name = "ec2_private_ip")
    private String privateIpAddress;

    /** Primary public IPv4 address, if assigned. */
    @Column(name = "ec2_public_ip")
    private String publicIpAddress;

    /** VPC ID the instance belongs to. */
    @Column(name = "ec2_vpc_id")
    private String vpcId;

    /** Subnet ID the instance resides in. */
    @Column(name = "ec2_subnet_id")
    private String subnetId;

    /** Current status (e.g., running, stopped, pending). */
    @Column(name = "ec2_status")
    private String status;
    // Add other relevant EC2 fields as needed

    /**
     * Constructor for EC2Resource.
     *
     * @param resourceTypeRecord The ResourceType record (must be for EC2 Instance).
     * @param instanceId         The EC2 instance ID.
     * @param name               The value of the Name tag, or null.
     */
    public EC2Resource(ResourceType resourceTypeRecord, String instanceId, String name) {
        super(resourceTypeRecord, instanceId, name); // Call AWSResource constructor
        // Validate using the mapped typeId from the superclass
        String typeId = getTypeId();
        if (!isEc2TypeId(typeId)) {
            System.err.println("Warning: Creating EC2Resource with unexpected ResourceType ID: " + typeId);
            // Consider throwing an IllegalArgumentException
        }
    }

    /**
     * No-arg constructor required by JPA.
     */
    protected EC2Resource() {
        super();
    }

    // --- Getters and Setters for EC2 specific fields ---

    public String getInstanceType() {
        return instanceType;
    }

    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    public String getImageId() {
        return imageId;
    }

    public void setImageId(String imageId) {
        this.imageId = imageId;
    }

    public String getPrivateIpAddress() {
        return privateIpAddress;
    }

    public void setPrivateIpAddress(String privateIpAddress) {
        this.privateIpAddress = privateIpAddress;
    }

    public String getPublicIpAddress() {
        return publicIpAddress;
    }

    public void setPublicIpAddress(String publicIpAddress) {
        this.publicIpAddress = publicIpAddress;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // Inherits other methods like toString(), getProviderSpecificType() if not
    // overridden.
}