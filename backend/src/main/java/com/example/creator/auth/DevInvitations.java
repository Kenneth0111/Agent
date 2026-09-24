package com.example.creator.auth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
class DevInvitations implements ApplicationRunner {
    private final InviteService invitations;
    private final String code;

    DevInvitations(InviteService invitations, @Value("${DEV_INVITE_CODE:}") String code) {
        this.invitations = invitations;
        this.code = code;
    }

    @Override
    public void run(ApplicationArguments args) {
        invitations.createDevInvitationIfAbsent(code, Duration.ofDays(7));
    }
}
