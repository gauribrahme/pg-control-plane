package com.gauribrahme.pgcp.provisioner;

import com.gauribrahme.pgcp.instance.Plan;

/** What the provisioner says really exists right now. */
public record ActualState(Plan plan, int storageGb, boolean ready, String endpoint) {}
