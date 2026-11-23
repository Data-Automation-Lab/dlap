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

package com.dlap.reporting.service.notification.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dlap.reporting.dto.NotificationRequestDTO;
import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.model.UserNotificationPreference;
import com.dlap.reporting.service.notification.INotificationProvider;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailNotificationProvider implements INotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProvider.class);

    private final JavaMailSender mailSender;

    @Value("${app.notifications.email.from:noreply@dalab.com}")
    private String fromEmail;

    /** Keys in {@link UserNotificationPreference#getChannelConfiguration()} used to resolve the recipient. */
    private static final String DEFAULT_USER_EMAIL_KEY = "emailAddress";

    @Autowired
    public EmailNotificationProvider(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.EMAIL;
    }

    @Async
    @Override
    public void send(UserNotificationPreference preference, NotificationRequestDTO request) {
        String toEmail = determineEmailAddress(preference);
        if (toEmail == null) {
            log.warn("No email address found for user {} in preference {}. Cannot send email notification.", 
                    preference.getUserId(), preference.getId());
            return;
        }
        sendEmail(toEmail, request.getSubject(), request.getBody(), request.getContentModel() != null); // Assuming HTML if contentModel exists
    }

    @Async
    @Override
    public void sendDirect(NotificationRequestDTO request, String targetEmailAddress) {
        sendEmail(targetEmailAddress, request.getSubject(), request.getBody(), request.getContentModel() != null);
    }

    private String determineEmailAddress(UserNotificationPreference preference) {
        if (preference.getChannelConfiguration() == null) {
            log.warn("No channelConfiguration for user {}.", preference.getUserId());
            return null;
        }
        var cfg = preference.getChannelConfiguration();
        String direct = firstNonBlank(
                cfg.get(DEFAULT_USER_EMAIL_KEY),
                cfg.get("email"),
                cfg.get("mail"),
                cfg.get("to"));
        if (direct != null) {
            return direct;
        }
        for (String v : cfg.values()) {
            if (StringUtils.hasText(v) && v.contains("@")) {
                return v.trim();
            }
        }
        log.warn("Email address not found in preference channelConfiguration for user {}.", preference.getUserId());
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private void sendEmail(String to, String subject, String body, boolean isHtml) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8"); // true for multipart

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, isHtml); // if isHtml is true, body should be HTML content

            // TODO: Add support for attachments or more complex HTML templates using contentModel if needed

            mailSender.send(mimeMessage);
            log.info("Email sent to {} with subject: {}", to, subject);
        } catch (MailException | MessagingException e) {
            log.error("Failed to send email to {} with subject: {}. Error: {}", to, subject, e.getMessage(), e);
            // Depending on the exception, might re-throw or handle (e.g., retry logic for MailSendException)
        }
    }
} 