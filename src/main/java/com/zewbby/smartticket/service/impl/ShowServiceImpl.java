package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.cache.CacheService;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.SessionVO;
import com.zewbby.smartticket.domain.vo.ShowDetailVO;
import com.zewbby.smartticket.domain.vo.ShowListVO;
import com.zewbby.smartticket.domain.vo.TicketCategoryVO;
import com.zewbby.smartticket.mapper.ShowMapper;
import com.zewbby.smartticket.service.ShowService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ShowServiceImpl implements ShowService {

    private static final Duration SHOW_DETAIL_TTL = Duration.ofMinutes(30);

    private static final Duration SHOW_SESSIONS_TTL = Duration.ofMinutes(10);

    private static final Duration SESSION_TICKET_CATEGORIES_TTL = Duration.ofMinutes(10);

    //初始化构造一个showMapper
    private final ShowMapper showMapper;

    private final CacheService cacheService;

    public ShowServiceImpl(ShowMapper showMapper, CacheService cacheService) {
        this.showMapper = showMapper;
        this.cacheService = cacheService;
    }

    /**
     * 打印
     * @return
     */
    @Override
    public List<ShowListVO> listShows() {
        return showMapper.selectShowList();
    }

    /**
     * 获取演出详情
     * @param showId
     * @return
     */
    @Override
    public ShowDetailVO getShowDetail(Long showId) {
        //生成key
        String cacheKey = RedisKeyConstant.showDetailKey(showId);
        //查cache，有的话就不走Mysql
        ShowDetailVO cachedShowDetail = cacheService.get(cacheKey);
        if (cachedShowDetail != null) {
            return cachedShowDetail;
        }

        //检查当前演出存不存在，存在了就接收这个VO，调mapper把查出来的sessions赋过来
        ShowDetailVO showDetailVO = getExistingShow(showId);
        showDetailVO.setSessions(listSessions(showId));
        cacheService.set(cacheKey, showDetailVO, SHOW_DETAIL_TTL);
        return showDetailVO;
    }

    /**
     * 查所有场次信息
     * @param showId
     * @return
     */
    @Override
    public List<SessionVO> listSessions(Long showId) {
        //cache
        String cacheKey = RedisKeyConstant.showSessionsKey(showId);
        List<SessionVO> cachedSessions = cacheService.get(cacheKey);
        if (cachedSessions != null) {
            return cachedSessions;
        }

        //先查询是否有这个用户，没有直接报错
        getExistingShow(showId);
        //根据id调mapper执行SQL查询对应的场次
        List<SessionVO> sessions = showMapper.selectSessionsByShowId(showId);
        //为每个演出给出他们的票档
        for (SessionVO session : sessions) {
            session.setTicketCategories(listTicketCategories(session.getId()));
        }
        cacheService.set(cacheKey, sessions, SHOW_SESSIONS_TTL);
        return sessions;
    }

    /**
     * 查票档信息
     * @param sessionId
     * @return
     */
    @Override
    public List<TicketCategoryVO> listTicketCategories(Long sessionId) {
        String cacheKey = RedisKeyConstant.sessionTicketCategoriesKey(sessionId);
        List<TicketCategoryVO> cachedTicketCategories = cacheService.get(cacheKey);
        if (cachedTicketCategories != null) {
            return cachedTicketCategories;
        }

        List<TicketCategoryVO> ticketCategories = showMapper.selectTicketCategoriesBySessionId(sessionId);
        cacheService.set(cacheKey, ticketCategories, SESSION_TICKET_CATEGORIES_TTL);
        return ticketCategories;
    }

    private ShowDetailVO getExistingShow(Long showId) {
        ShowDetailVO showDetailVO = showMapper.selectShowDetailById(showId);
        if (showDetailVO == null) {
            throw new BusinessException("演出不存在");
        }
        return showDetailVO;
    }
}
