package com.gauribrahme.pgcp.instance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateInstanceRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,40}[a-z0-9]$", message = "must be a lowercase DNS-style name, 3-42 chars")
        String name,
        @NotNull Plan plan,
        @Min(10) @Max(8000) Integer storageGb,
        @Pattern(regexp = "^(14|15|16)$", message = "supported versions: 14, 15, 16") String pgVersion) {
}
