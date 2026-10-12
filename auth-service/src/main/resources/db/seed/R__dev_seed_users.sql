-- DEV-ONLY seed user (login: testuser@test.com / password123). Never ship this to a real environment.
-- Lives in db/seed, which is only added to spring.flyway.locations in docker-compose / LocalStack.
INSERT INTO users (id, email, password, role)
VALUES ('223e4567-e89b-12d3-a456-426614174006', 'testuser@test.com',
        '$2b$12$7hoRZfJrRKD2nIm2vHLs7OBETy.LWenXXMLKf99W8M4PUwO6KB7fu', 'ADMIN')
    ON CONFLICT DO NOTHING;