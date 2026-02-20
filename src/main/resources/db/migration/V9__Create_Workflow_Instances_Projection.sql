-- V9: Create Workflow Instances Projection Table
-- Read-side projection for workflow instance queries and counts.
-- Maintained by WorkflowInstanceProjection processing the event stream.

CREATE TABLE IF NOT EXISTS workflow_instances_projection (
    instance_id       UUID            PRIMARY KEY,
    workflow_id       VARCHAR(255)    NOT NULL,
    workflow_name     VARCHAR(255),
    workflow_version  VARCHAR(50),
    status            VARCHAR(50)     NOT NULL,
    current_step_id   VARCHAR(255),
    correlation_id    VARCHAR(255),
    triggered_by      VARCHAR(255),
    error_message     TEXT,
    error_type        VARCHAR(255),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    last_updated      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version           BIGINT          NOT NULL DEFAULT 0,
    deleted           BOOLEAN         NOT NULL DEFAULT FALSE
);

-- Indexes aligned to the 8 query methods (all partial: WHERE deleted = FALSE)
CREATE INDEX idx_wip_workflow_id    ON workflow_instances_projection(workflow_id) WHERE deleted = FALSE;
CREATE INDEX idx_wip_status         ON workflow_instances_projection(status) WHERE deleted = FALSE;
CREATE INDEX idx_wip_wf_status      ON workflow_instances_projection(workflow_id, status) WHERE deleted = FALSE;
CREATE INDEX idx_wip_correlation_id ON workflow_instances_projection(correlation_id) WHERE deleted = FALSE AND correlation_id IS NOT NULL;
CREATE INDEX idx_wip_active         ON workflow_instances_projection(status) WHERE deleted = FALSE AND status IN ('PENDING','RUNNING','WAITING');
CREATE INDEX idx_wip_stale          ON workflow_instances_projection(status, started_at) WHERE deleted = FALSE AND status IN ('RUNNING','WAITING');
