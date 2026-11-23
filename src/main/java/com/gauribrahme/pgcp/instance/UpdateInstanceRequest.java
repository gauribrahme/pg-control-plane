package com.gauribrahme.pgcp.instance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Partial update: at least one of plan / storageGb. Storage can only grow. */
public record UpdateInstanceRequest(Plan plan, @Min(10) @Max(8000) Integer storageGb) {
}
