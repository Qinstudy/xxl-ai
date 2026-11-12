package com.xxl.ai.api.business.workflow.controller;

import com.xxl.ai.api.business.space.model.SpaceContext;
import com.xxl.ai.api.business.space.service.SpaceService;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.ai.api.business.workflow.service.WorkflowExecutionService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作流执行记录 Controller
 *
 * @author xxl-ai 2026-11-12
 */
@RestController
@RequestMapping("/workflow/execution")
public class WorkflowExecutionController {

    @Resource
    private WorkflowExecutionService workflowExecutionService;

    @Resource
    private SpaceService spaceService;

    @RequestMapping("/pageList")
    @XxlSso(permission = "workflow:default")
    public Response<PageModel<WorkflowExecutionDTO>> pageList(HttpServletRequest request,
                                                              @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                              @RequestParam(required = false) Long workflowId,
                                                              @RequestParam(required = false, defaultValue = "0") int offset,
                                                              @RequestParam(required = false, defaultValue = "10") int pagesize,
                                                              @RequestParam(required = false) Integer status) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        PageModel<WorkflowExecutionDTO> pageModel = workflowExecutionService.pageList(spaceResp.getData().getSpaceId(), workflowId, offset, pagesize, status);
        return Response.ofSuccess(pageModel);
    }

    @RequestMapping("/load")
    @XxlSso(permission = "workflow:default")
    public Response<WorkflowExecutionDTO> load(HttpServletRequest request,
                                               @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                               @RequestParam("id") long id) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowExecutionService.load(spaceResp.getData().getSpaceId(), id);
    }

    @PostMapping("/delete")
    @XxlSso(permission = "workflow:default")
    public Response<String> delete(HttpServletRequest request,
                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                   @RequestBody List<Long> ids) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowExecutionService.delete(spaceResp.getData().getSpaceId(), ids);
    }
}
