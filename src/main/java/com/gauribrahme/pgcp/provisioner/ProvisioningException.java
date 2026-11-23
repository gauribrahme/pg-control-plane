package com.gauribrahme.pgcp.provisioner;

public class ProvisioningException extends RuntimeException {
    public ProvisioningException(String message) { super(message); }
    public ProvisioningException(String message, Throwable cause) { super(message, cause); }
}
