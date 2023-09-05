package com.winnow.bestchoice.service;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.winnow.bestchoice.config.jwt.TokenProvider;
import com.winnow.bestchoice.entity.*;
import com.winnow.bestchoice.exception.CustomException;
import com.winnow.bestchoice.exception.ErrorCode;
import com.winnow.bestchoice.model.dto.PostDetailDto;
import com.winnow.bestchoice.model.dto.PostSummaryDto;
import com.winnow.bestchoice.model.request.CreatePostForm;
import com.winnow.bestchoice.model.response.PostDetailRes;
import com.winnow.bestchoice.model.response.PostRes;
import com.winnow.bestchoice.repository.*;
import com.winnow.bestchoice.type.MyPageSort;
import com.winnow.bestchoice.type.Option;
import com.winnow.bestchoice.type.PostSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final PostQueryRepository postQueryRepository;
    private final MemberRepository memberRepository;
    private final TagRepository tagRepository;
    private final PostTagRepository postTagRepository;
    private final PostLikeRepository postLikeRepository;
    private final ChoiceRepository choiceRepository;
    private final AmazonS3Client amazonS3Client;
    private final TokenProvider tokenProvider;
    @Value("${cloud.aws.s3.bucket}")
    private String bucket;


    public PostDetailRes createPost(CreatePostForm createPostForm, List<MultipartFile> files, Authentication authentication) { // 최적화 - tag 한 번에?
        long memberId = tokenProvider.getMemberId(authentication);
        Member member = memberRepository.findById(memberId).
                orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));// 쿼리 최적화

        Post post = postRepository.save(createPostForm.toEntity(member));

        List<String> tags = createPostForm.getTags();
        List<PostTag> postTags = new ArrayList<>();

        for (String tagName : tags) { //존재하는 태그면 가져오고 없으면 태그 생성
            Tag tag = tagRepository.findByName(tagName).orElseGet(() -> tagRepository.save(new Tag(tagName)));
            postTags.add(new PostTag(post, tag));
        }

        if (!postTags.isEmpty()) {
            postTagRepository.saveAll(postTags);
        }

        //TODO S3 저장 로직 구현
        ArrayList<String> resources = new ArrayList<>();
        if (!ObjectUtils.isEmpty(files)) {
            try {
                for (MultipartFile file : files) {
                    ObjectMetadata metadata = new ObjectMetadata();
                    metadata.setContentType(file.getContentType());
                    metadata.setContentLength(file.getSize());
                    String fileName = UUID.randomUUID().toString();
                    amazonS3Client.putObject(bucket, fileName, file.getInputStream(), metadata);
                    resources.add(amazonS3Client.getResourceUrl(bucket, fileName));
                }
            } catch (Exception e) {
                log.info("exception is occurred when S3 upload in createPost");
                throw new CustomException(ErrorCode.SERVER_ERROR);
            }
        }

        PostDetailRes postDetail = PostDetailRes.of(post);
        postDetail.setResources(resources);

        return postDetail;
    }

    public void likePost(Authentication authentication, long postId) {
        Long memberId = tokenProvider.getMemberId(authentication);

        if (!memberRepository.existsById(memberId)) {
            throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (!postRepository.existsById(postId)) {
            throw new CustomException(ErrorCode.POST_NOT_FOUND);
        }
        if (postLikeRepository.existsByPost_IdAndMember_Id(postId, memberId)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        postLikeRepository.save(new PostLike(new Member(memberId), new Post(postId)));
        postRepository.plusLikeCountById(postId);
    }

    public void unlikePost(Authentication authentication, long postId) { //최적화
        Long memberId = tokenProvider.getMemberId(authentication);

        PostLike postLike = postLikeRepository.findByPost_IdAndMember_Id(postId, memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REQUEST));

        postLikeRepository.delete(postLike);
        postRepository.minusLikeCountById(postId);
    }

    public void choiceOption(Authentication authentication, long postId, Option choice) {
        Long memberId = tokenProvider.getMemberId(authentication);

        if (!memberRepository.existsById(memberId)) {
            throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        }

        if (!postRepository.existsById(postId)) {
            throw new CustomException(ErrorCode.POST_NOT_FOUND);
        }

        Member member = memberRepository.getReferenceById(memberId);
        Post post = postRepository.getReferenceById(postId);

        if (choiceRepository.existsByPostAndMember(post, member)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        choiceRepository.save(Choice.builder()
                .member(member)
                .post(post)
                .choices(choice).build());

        switch (choice) {
            case A : postRepository.plusACountById(postId); break;
            case B : postRepository.plusBCountById(postId); break;
            default: throw new IllegalStateException("Unexpected value: " + choice);
        }
    }

    public PostDetailRes getPostDetail(Authentication authentication, long postId) {
        long memberId = 0;
        if (!ObjectUtils.isEmpty(authentication)) {
            memberId = tokenProvider.getMemberId(authentication);
        }
        PostDetailDto postDetailDto = postQueryRepository.getPostDetail(postId, memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.POST_NOT_FOUND));

        postDetailDto.setResources(Collections.emptyList()); //TODO S3 로직 구현 후 삭제

        return PostDetailRes.of(postDetailDto);
    }

    public Slice<PostRes> getPosts(int page, int size, PostSort sort) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Slice<PostSummaryDto> slice = postQueryRepository.getSlice(pageRequest, sort.getType());
        return slice.map(PostRes::of);
    }

    public Slice<PostRes> getMyPage(Authentication authentication, int page, int size, MyPageSort sort) {
        long memberId = tokenProvider.getMemberId(authentication);
        PageRequest pageRequest = PageRequest.of(page, size);
        Slice<PostSummaryDto> postSlice;

        switch (sort) {
            case POSTS : postSlice = postQueryRepository.getSliceByMemberId(pageRequest, memberId); break;
            case LIKES : postSlice = postQueryRepository.getSliceFromLikes(pageRequest, memberId); break;
            case CHOICES : postSlice = postQueryRepository.getSliceFromChoices(pageRequest, memberId); break;
            case COMMENTS : postSlice = postQueryRepository.getSliceFromComments(pageRequest, memberId); break;
            default: throw new IllegalStateException("Unexpected value: " + sort);
        }

        return postSlice.map(PostRes::of);
    }

    public Slice<PostRes> getPostsByTag(int page, int size, String tag) {
        PageRequest pageRequest = PageRequest.of(page, size);

        return postQueryRepository.getSliceByTag(pageRequest, tag).map(PostRes::of);
    }

    public void deletePost(Authentication authentication, long postId) {
        long memberId = tokenProvider.getMemberId(authentication);
        if (!postQueryRepository.existsByPostIdAndMemberId(postId, memberId)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        postQueryRepository.deletePost(postId);
    }
}
