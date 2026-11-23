package com.gauribrahme.pgcp.instance;

import com.gauribrahme.pgcp.common.ApiException;
import org.springframework.http.HttpStatus;

public class IllegalStateTransitionException extends ApiException {
    public IllegalStateTransitionException(InstanceState from, InstanceState to) {
        super(HttpStatus.CONFLICT, "illegal-state-transition",
                "Instance cannot move from " + from + " to " + to);
    }
}
