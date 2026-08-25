package com.kevin.hapeetrail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 測試自己鑄的 GoTrue 形狀 token。公鑰由 {@link SupabaseDbTest} 餵給
 * {@code spring.security.oauth2.resourceserver.jwt.public-key-location}，
 * 取代 hosted 的 JWKS——測試因此不需要網路，也不需要專案的任何金鑰。
 */
final class TestJwt {

	/** 服務認得的那把。 */
	static final RSAKey SIGNING_KEY = generate();

	/** 服務不認得的那把——「簽章不符」那條變形用。 */
	static final RSAKey FOREIGN_KEY = generate();

	private TestJwt() {
	}

	/** 一個剛匿名登入的旅人會拿到的東西。 */
	static String valid(UUID subject) {
		return token(SIGNING_KEY, subject.toString(), "authenticated", Instant.now().plusSeconds(3600));
	}

	/**
	 * @param subject null ＝ 不放 {@code sub}
	 * @param audience null ＝ 不放 {@code aud}
	 */
	static String token(RSAKey key, String subject, String audience, Instant expiresAt) {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
			.issueTime(Date.from(Instant.now().minusSeconds(60)))
			.expirationTime(Date.from(expiresAt))
			// GoTrue 會放，但服務不看它——匿名與正式帳號在服務端無差別。
			.claim("is_anonymous", true);
		if (subject != null) {
			claims.subject(subject);
		}
		if (audience != null) {
			claims.audience(audience);
		}
		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims.build());
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 公鑰寫成 PEM 暫存檔，回傳給 Spring 的 {@code public-key-location}。 */
	static Path publicKeyFile() {
		try {
			String pem = "-----BEGIN PUBLIC KEY-----\n"
					+ Base64.getMimeEncoder(64, new byte[] { '\n' })
						.encodeToString(SIGNING_KEY.toRSAPublicKey().getEncoded())
					+ "\n-----END PUBLIC KEY-----\n";
			Path file = Files.createTempFile("hapeetrail-test-jwt-", ".pem");
			file.toFile().deleteOnExit();
			return Files.writeString(file, pem);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static RSAKey generate() {
		try {
			return new RSAKeyGenerator(2048).generate();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
