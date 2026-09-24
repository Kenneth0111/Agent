package com.example.creator.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final CurrentUser currentUser;
    private final UserRepository users;

    AuthController(CurrentUser currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) { return new CsrfView(token.getHeaderName(), token.getToken()); }

    @GetMapping("/me")
    public UserView me() {
        var user = users.findById(currentUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName());
    }

    public record CsrfView(String headerName, String token) { }
    public record UserView(long id, String email, String displayName) { }
}
