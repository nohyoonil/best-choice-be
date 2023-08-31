package com.winnow.bestchoice.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter
public class PostDetailDto {

    private long id;
    private long memberId;
    private String nickname;
    private String title;
    private String content;
    private String optionA;
    private String optionB;
    private List<String> tags;
    private List<String> resources;
    private LocalDateTime createdDate;
    private LocalDateTime popularityDate;
    private long likeCount;
    private long ACount;
    private long BCount;
    private long commentCount;
}
