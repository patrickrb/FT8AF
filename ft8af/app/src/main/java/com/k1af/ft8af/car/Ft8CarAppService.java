package com.k1af.ft8af.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * Android Auto entry point. Templated Car App (androidx.car.app) that surfaces the live FT8
 * decode list / QSO status on a connected head unit.
 *
 * <p>NOTE: an FT8 decoder fits none of Google's distributable Car App categories
 * (navigation / parking / charging / POI), so this is <b>developer / Desktop-Head-Unit (DHU)
 * only</b> — it is declared with the IOT category and uses
 * {@link HostValidator#ALLOW_ALL_HOSTS_VALIDATOR}, which must be tightened before any
 * production distribution.
 */
public class Ft8CarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Dev/DHU only — accept any host. Replace with an allow-list before shipping.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Ft8Session();
    }
}
