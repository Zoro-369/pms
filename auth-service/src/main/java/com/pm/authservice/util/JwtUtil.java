package com.pm.authservice.util;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private static final int MINIMUM_SECRET_BYTES = 32;

  private final Key secretKey;

  public JwtUtil(@Value("${jwt.secret}") String secret) {
    this.secretKey = Keys.hmacShaKeyFor(resolveSecretKey(secret));
  }

  private byte[] resolveSecretKey(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("JWT secret must not be blank");
    }

    byte[] rawSecret = secret.getBytes(StandardCharsets.UTF_8);

    try {
      byte[] decoded = Base64.getDecoder().decode(secret);
      if (decoded.length >= MINIMUM_SECRET_BYTES) {
        return decoded;
      }
    } catch (IllegalArgumentException ignored) {
      // Fall back to the raw secret value when the provided key is not base64.
    }

    if (rawSecret.length >= MINIMUM_SECRET_BYTES) {
      return rawSecret;
    }

    try {
      return MessageDigest.getInstance("SHA-256").digest(rawSecret);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available to create a JWT signing key", e);
    }
  }

  public String generateToken(String email, String role) {
    return Jwts.builder()
        .subject(email)
        .claim("role", role)
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 *10)) // 10 hours
        .signWith(secretKey)
        .compact();
  }

  public void validateToken(String token) {
    try {
      Jwts.parser().verifyWith((SecretKey) secretKey)
          .build()
          .parseSignedClaims(token);
    } catch (SignatureException e) {
      throw new JwtException("Invalid JWT signature");
    } catch (JwtException e) {
      throw new JwtException("Invalid JWT");
    }
  }
}
