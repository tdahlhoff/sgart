-- Story 3.6: reserved sub-state for the two-phase item transfer saga. ItemTransferInitiated sets
-- this TRUE (row kept, not removed); ItemTransferConfirmed removes the row; ItemTransferCancelled
-- clears it back to FALSE. Not personal data — a transfer-in-flight marker only.
ALTER TABLE item_read_model ADD COLUMN transfer_pending BOOLEAN NOT NULL DEFAULT FALSE;
