package com.example.a_track.utils;

import android.os.AsyncTask;
import android.util.Log;
import java.io.File;
import java.util.Properties;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class EmailSender {

    private static final String TAG = "EmailSender";

    // Gmail SMTP Configuration
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "587";

    // Your Gmail credentials (Use App Password, not regular password)
    private static final String SENDER_EMAIL = "a.track1234@gmail.com"; // Change this
    private static final String SENDER_PASSWORD = "ciek hngh hrgn zghk"; // Change this (Use App Password)

    public interface EmailCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public static void sendEmailWithAttachment(
            String recipientEmail,
            String subject,
            String body,
            File attachment,
            EmailCallback callback) {

        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    // SMTP Configuration
                    Properties props = new Properties();
                    props.put("mail.smtp.host", SMTP_HOST);
                    props.put("mail.smtp.port", SMTP_PORT);
                    props.put("mail.smtp.auth", "true");
                    props.put("mail.smtp.starttls.enable", "true");

                    // Create session with authentication
                    Session session = Session.getInstance(props, new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(SENDER_EMAIL, SENDER_PASSWORD);
                        }
                    });

                    // Create email message
                    Message message = new MimeMessage(session);
                    message.setFrom(new InternetAddress(SENDER_EMAIL));
                    message.setRecipients(Message.RecipientType.TO,
                            InternetAddress.parse(recipientEmail));
                    message.setSubject(subject);

                    // Create message body
                    BodyPart messageBodyPart = new MimeBodyPart();
                    messageBodyPart.setText(body);

                    // Create multipart message
                    Multipart multipart = new MimeMultipart();
                    multipart.addBodyPart(messageBodyPart);

                    // Attach file if provided
                    if (attachment != null && attachment.exists()) {
                        MimeBodyPart attachmentPart = new MimeBodyPart();
                        DataSource source = new FileDataSource(attachment);
                        attachmentPart.setDataHandler(new DataHandler(source));
                        attachmentPart.setFileName(attachment.getName());
                        multipart.addBodyPart(attachmentPart);
                    }

                    // Set content
                    message.setContent(multipart);

                    // Send email
                    Transport.send(message);

                    Log.d(TAG, "Email sent successfully to: " + recipientEmail);
                    return null; // Success

                } catch (MessagingException e) {
                    Log.e(TAG, "Failed to send email: " + e.getMessage());
                    e.printStackTrace();
                    return e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String error) {
                if (error == null) {
                    callback.onSuccess();
                } else {
                    callback.onFailure(error);
                }
            }
        }.execute();
    }
}