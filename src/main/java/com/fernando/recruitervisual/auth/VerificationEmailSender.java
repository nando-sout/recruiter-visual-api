package com.fernando.recruitervisual.auth;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envia os códigos de verificação de e-mail e de recuperação de senha via SMTP (spring.mail.*).
 * Falhas de envio propagam como MailException.
 */
@Service
public class VerificationEmailSender {

	private final JavaMailSender mailSender;
	private final String from;

	public VerificationEmailSender(JavaMailSender mailSender, @Value("${spring.mail.from}") String from) {
		this.mailSender = mailSender;
		this.from = from;
	}

	public void sendVerificationCode(String to, String code, Duration validity) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject("Seu código de verificação - Recruiter Visual");
		message.setText("""
				Seu código de verificação é: %s

				Ele expira em %d minutos.

				Se você não criou uma conta no Recruiter Visual, ignore este e-mail.
				""".formatted(code, validity.toMinutes()));
		mailSender.send(message);
	}

	public void sendPasswordResetCode(String to, String code, Duration validity) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject("Recuperação de senha - Recruiter Visual");
		message.setText("""
				Recebemos um pedido de recuperação de senha para a sua conta no Recruiter Visual.

				Seu código de recuperação é: %s

				Ele expira em %d minutos. Não compartilhe este código com ninguém.

				Se você não pediu a recuperação de senha, ignore este e-mail: sua senha continua a mesma.
				""".formatted(code, validity.toMinutes()));
		mailSender.send(message);
	}

}
