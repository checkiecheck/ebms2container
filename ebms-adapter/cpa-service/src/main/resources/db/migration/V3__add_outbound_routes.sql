CREATE TABLE cpa_outbound_route (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cpa_id           VARCHAR(255) NOT NULL
                                 REFERENCES collaboration_protocol_agreement(cpa_id)
                                 ON DELETE CASCADE,
    from_party_id    VARCHAR(255) NOT NULL,
    to_party_id      VARCHAR(255) NOT NULL,
    service          VARCHAR(255) NOT NULL,
    service_type     VARCHAR(100),
    action           VARCHAR(100) NOT NULL,
    action_binding_id VARCHAR(255) NOT NULL,
    from_role        VARCHAR(100) NOT NULL,
    to_role          VARCHAR(100) NOT NULL,
    channel_party_id VARCHAR(255) NOT NULL,
    channel_id       VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_outbound_action_channel UNIQUE (
        cpa_id, from_party_id, action_binding_id, channel_id
    )
);

CREATE INDEX idx_outbound_route_lookup ON cpa_outbound_route (
    cpa_id, from_party_id, to_party_id
);

COMMENT ON TABLE cpa_outbound_route IS
    'Afgeleide CPPA CanSend-routes met exacte partijen, rollen, service, action en channel';