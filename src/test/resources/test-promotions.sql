DELETE FROM promo_codes;
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_users, max_orders_per_user, campaign_group)
VALUES ('00000000-0000-4000-9000-000000005001','FIRST50','USER_LIMIT',50,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z',2,1,'WELCOME');
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_users, max_orders_per_user, campaign_group, prerequisite_code, prerequisite_paid_users)
VALUES ('00000000-0000-4000-9000-000000003001','FIRST30','USER_LIMIT',30,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z',8,1,'WELCOME','FIRST50',2);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_users, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000101','TESTFIRST','USER_LIMIT',10,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z',2,1);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000102','TESTDATE','DATE',10,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z',1);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000103','TESTFIRSTORDER','FIRST_ORDER',10,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z',1);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000104','TESTSEVERALORDERS','SUBSEQUENT_ORDERS',10,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z',3);
