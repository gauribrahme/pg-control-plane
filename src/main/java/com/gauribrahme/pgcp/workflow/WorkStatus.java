package com.gauribrahme.pgcp.workflow;

/** PENDING also covers "waiting for retry": nextAttemptAt in the future means backoff. */
public enum WorkStatus { PENDING, IN_PROGRESS, DONE, FAILED }
