package com.xxl.ai.api.business.eval.controller;

import com.xxl.ai.api.business.eval.model.dto.EvalExperimentDTO;
import com.xxl.ai.api.business.eval.service.EvalExperimentService;
import com.xxl.ai.api.business.space.model.SpaceContext;
import com.xxl.ai.api.business.space.service.SpaceService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评测实验 Controller
 *
 * @author xxl-ai 2026-11-12
 */
@RestController
@RequestMapping("/eval/experiment")
public class EvalExperimentController {

    @Resource
    private EvalExperimentService evalExperimentService;

    @Resource
    private SpaceService spaceService;

    @RequestMapping("/pageList")
    @XxlSso(permission = "eval:default")
    public Response<PageModel<EvalExperimentDTO>> pageList(HttpServletRequest request,
                                                           @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                           @RequestParam(required = false, defaultValue = "0") int offset,
                                                           @RequestParam(required = false, defaultValue = "10") int pagesize,
                                                           String name,
                                                           Long datasetId,
                                                           @RequestParam(required = false, defaultValue = "-1") Integer status) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        PageModel<EvalExperimentDTO> pageModel = evalExperimentService.pageList(spaceResp.getData().getSpaceId(), offset, pagesize, name, datasetId, status);
        return Response.ofSuccess(pageModel);
    }

    @RequestMapping("/load")
    @XxlSso(permission = "eval:default")
    public Response<EvalExperimentDTO> load(HttpServletRequest request,
                                            @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                            @RequestParam("id") long id) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return evalExperimentService.load(spaceResp.getData().getSpaceId(), id);
    }

    @PostMapping("/createAndRun")
    @XxlSso(permission = "eval:default")
    public Response<Long> createAndRun(HttpServletRequest request,
                                       @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                       @RequestBody EvalExperimentDTO experimentDTO) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return evalExperimentService.createAndRun(spaceResp.getData().getSpaceId(), experimentDTO);
    }

    @PostMapping("/cancel")
    @XxlSso(permission = "eval:default")
    public Response<String> cancel(HttpServletRequest request,
                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                   @RequestParam("id") long id) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return evalExperimentService.cancel(spaceResp.getData().getSpaceId(), id);
    }

    @PostMapping("/delete")
    @XxlSso(permission = "eval:default")
    public Response<String> delete(HttpServletRequest request,
                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                   @RequestBody List<Long> ids) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return evalExperimentService.delete(spaceResp.getData().getSpaceId(), ids);
    }
}
