-- Representative subset of NIP institution codes (seed table required by the assessment).
INSERT INTO supported_bank (bank_code, bank_name) VALUES
    ('000001', 'Sterling Bank'),
    ('000002', 'Keystone Bank'),
    ('000003', 'First City Monument Bank'),
    ('000004', 'United Bank for Africa'),
    ('000007', 'Fidelity Bank'),
    ('000008', 'Polaris Bank'),
    ('000010', 'Ecobank Nigeria'),
    ('000011', 'Unity Bank'),
    ('000012', 'Stanbic IBTC Bank'),
    ('000013', 'Guaranty Trust Bank'),
    ('000014', 'Access Bank'),
    ('000015', 'Zenith Bank'),
    ('000016', 'First Bank of Nigeria'),
    ('000017', 'Wema Bank'),
    ('000018', 'Union Bank of Nigeria'),
    ('000023', 'Providus Bank');

-- Demo accounts for the README curl calls.
-- 1012345678: NGN 100,000,000.00   1098765432: NGN 1,000.00 (for INSUFFICIENT_FUNDS demos)
INSERT INTO corporate_account (account_id, corporate_id, currency, available_kobo, earmarked_kobo, debited_kobo) VALUES
    ('1012345678', 'CORP-001', 'NGN', 10000000000, 0, 0),
    ('1098765432', 'CORP-002', 'NGN', 100000, 0, 0);
