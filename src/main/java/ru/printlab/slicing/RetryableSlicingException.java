package ru.printlab.slicing;

import ru.printlab.common.BusinessException;

public class RetryableSlicingException extends BusinessException {
    public RetryableSlicingException(String message) {
        super(message);
    }
}
