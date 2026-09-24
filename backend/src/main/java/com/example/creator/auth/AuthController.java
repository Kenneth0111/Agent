package com.example.creator.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final InviteService invitations;

    AuthController(CurrentUser currentUser, UserRepository users, InviteService invitations) {
        this.currentUser = currentUser;
        this.users = users;
        this.invitations = invitations;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) { return new CsrfView(token.getHeaderName(), token.getToken()); }

    @GetMapping("/me")
    public UserView me() {
        var user = users.findById(currentUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName());
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody(required = false) RegisterRequest request) {
        if (request == null) {
            return rejectedRegistration();
        }
        try {
            var user = invitations.register(new InviteService.Registration(request.invitationCode(), request.email(),
                    request.displayName(), request.password()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new UserView(user.getId(), user.getEmail(), user.getDisplayName()));
        } catch (InviteService.RegistrationRejected rejected) {
            return rejectedRegistration();
        }
    }

    private ResponseEntity<ErrorView> rejectedRegistration() {
        return ResponseEntity.badRequest().body(new ErrorView("REGISTRATION_REJECTED"));
    }

    public record CsrfView(String headerName, String token) { }
    public record UserView(long id, String email, String displayName) { }
    public record RegisterRequest(String invitationCode, String email, String displayName, String password) { }
    public record ErrorView(String code) { }
}
