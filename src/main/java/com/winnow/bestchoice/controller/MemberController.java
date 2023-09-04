package com.winnow.bestchoice.controller;

import com.winnow.bestchoice.config.jwt.TokenProvider;
import com.winnow.bestchoice.model.request.CheckNicknameRequest;
import com.winnow.bestchoice.model.request.UpdateNicknameRequest;
import com.winnow.bestchoice.model.response.CheckNicknameResponse;
import com.winnow.bestchoice.model.response.MemberDetailRes;
import com.winnow.bestchoice.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;

    @PutMapping
    public ResponseEntity<?> updateNickname(@RequestBody @Valid UpdateNicknameRequest request, Authentication authentication) {
        memberService.updateNickname(request.getNickname(), authentication);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/nickname-check")
    public ResponseEntity<?> checkNickname(@RequestBody CheckNicknameRequest request) {
        Boolean result = memberService.validNickname(request.getNickname());

        return new ResponseEntity<>(new CheckNicknameResponse(result), HttpStatus.OK);
    }

    @GetMapping("/mypage")
    public ResponseEntity<MemberDetailRes> getMyInfo(Authentication authentication) {
        return ResponseEntity.ok(memberService.getMemberDetail(authentication));
    }
}
