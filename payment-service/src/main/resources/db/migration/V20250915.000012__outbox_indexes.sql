-- Speed up leasing “ready to publish” rows per tenant.
-- (No partial indexes on partitioned tables, so use composite normals.)
CREATE INDEX IF NOT EXISTS idx_outbox_tenant_lease_attempts_created
    ON outbox (tenant_id, lease_until, attempts, created_at);

-- Helpful when queries include the partition key explicitly
CREATE INDEX IF NOT EXISTS idx_outbox_created_on_tenant_lease
    ON outbox (created_on, tenant_id, lease_until);