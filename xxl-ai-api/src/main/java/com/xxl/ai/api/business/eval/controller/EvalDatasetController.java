package com.xxl.ai.api.business.eval.controller;

import com.xxl.ai.api.business.eval.model.dto.EvalDatasetDTO;
import com.xxl.ai.api.business.eval.service.EvalDatasetService;
import com.xxl.ai.api.business.space.model.SpaceContext;
import com.xxl.ai.api.business.space.service.SpaceService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评测数据集 Controller
 *
 * @author xxl-ai 2026-11-12
 */
@RestController
@RequestMapping("/eval/dataset")
public class EvalDatasetController {

    @Resource
    private EvalDatasetService evalDatasetService;

    @Resource
    private SpaceService spaceService;

    @RequestMapping("/pageList")
    @XxlSso(permission = "eval:default")
    public Response<PageModel<EvalDatasetDTO>> pageList(HttpServletRequest request,
                                                        @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                        @RequestParam(required = false, defaultValue = "0") int offset,
                                                        @RequestParam(required = false, defaultValue = "10") int pagesize,
                                                        String name,
                                                        String evalType) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        PageModel<EvalDatasetDTO> pageModel = evalDatasetService.pageList(spaceResp.getData().getSpaceId(), offset, pagesize, name, evalType);
        return Response.ofSuccess(pageModel);
    }

    @RequestMapping("/load")
    @XxlSso(permission = "eval:default")
    public Response<EvalDatasetDTO> load(@RequestParam("id") long id) {
        return evalDatasetService.load(id);
    }

    @PostMapping("/insert")
    @XxlSso(permission = "eval:default")
    public Response<String> insert(HttpServletRequest request,
                                  @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                  @RequestBody EvalDatasetDTO datasetDTO) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return evalDatasetService.insert(spaceResp.getData().getSpaceId(), datasetDTO);
    }

    @PostMapping("/update")
    @XxlSso(permission = "eval:default")
    public Response<String> update(HttpServletRequest request,
                                  @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                  @RequestBody EvalDatasetDTO datasetDTO) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return evalDatasetService.update(spaceResp.getData().getSpaceId(), datasetDTO);
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
        return evalDatasetService.delete(spaceResp.getData().getSpaceId(), ids);
    }

    @PostMapping("/importJson")
    @XxlSso(permission = "eval:default")
    public Response<String> importJson(HttpServletRequest request,
                                      @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                      @RequestBody Map<String, Object> params) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        long id = 0L;
        if (params.get("id") != null) {
            id = Long.parseLong(params.get("id").toString());
        }
        String jsonContent = (String) params.get("jsonContent");
        return evalDatasetService.importJson(spaceResp.getData().getSpaceId(), id, jsonContent);
    }
}
