package com.pm.authservice.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class JwtUtilTest {

  @Test
  void shouldInitializeFromWeakBase64Secret() {
    String secret = "Y2hhVEc3aHJnb0hYTzMyZ2ZqVkpiZ1RkZG93YWxrUkM=";

    JwtUtil jwtUtil = assertDoesNotThrow(() -> new JwtUtil(secret));
    String token = jwtUtil.generateToken("alice@example.com", "ADMIN");

    assertDoesNotThrow(() -> jwtUtil.validateToken(token));
  }

  @Test
  void shouldInitializeFromSecureSecret() {
    String secret = "UG1zQXV0aFNlcnZpY2VKV1RTZWNyZXRLZXkxMjM0NTY=";

    JwtUtil jwtUtil = assertDoesNotThrow(() -> new JwtUtil(secret));
    String token = jwtUtil.generateToken("bob@example.com", "USER");

    assertDoesNotThrow(() -> jwtUtil.validateToken(token));
  }
}
