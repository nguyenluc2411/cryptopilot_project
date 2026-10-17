package com.cryptopilot.notification;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * A JavaMailSender that keeps what it is given instead of talking to a server, and can be told to fail like an
 * unreachable one. The messages are built by the real Jakarta Mail classes; only the transport is replaced.
 */
public class RecordingMailSender extends JavaMailSenderImpl {

    private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
        if (failing) {
            throw new MailSendException("Couldn't connect to host (test)");
        }
        for (MimeMessage message : mimeMessages) {
            try {
                message.saveChanges();
            } catch (MessagingException broken) {
                throw new MailSendException("message could not be finished", broken);
            }
            sent.add(message);
        }
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public List<MimeMessage> sent() {
        return new ArrayList<>(sent);
    }

    public void clear() {
        sent.clear();
        failing = false;
    }

    /** The first part of the given type, e.g. {@code text/plain}, searched through nested multiparts. */
    public static String partOf(Part part, String mimeType) {
        try {
            if (part.isMimeType(mimeType)) {
                return (String) part.getContent();
            }
            if (part.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    String found = partOf(child, mimeType);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        } catch (MessagingException failure) {
            throw new IllegalStateException(failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
