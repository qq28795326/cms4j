package cn.im47.cms.common.service.article.impl;

import cn.im47.cms.common.dao.article.ArticleMapper;
import cn.im47.cms.common.dao.category.CategoryMapper;
import cn.im47.cms.common.dao.link.LinkMapper;
import cn.im47.cms.common.entity.account.User;
import cn.im47.cms.common.entity.article.Article;
import cn.im47.cms.common.entity.category.Category;
import cn.im47.cms.common.entity.link.Link;
import cn.im47.cms.common.service.account.ShiroDbRealm;
import cn.im47.cms.common.service.article.ArchiveManager;
import cn.im47.cms.common.service.article.ArticleManager;
import cn.im47.cms.jms.NotifyMessageProducer;
import cn.im47.cms.memcached.MemcachedObjectType;
import cn.im47.cms.utilities.freemarker.FreemakerHelper;
import cn.im47.commons.utilities.analyzer.ArticleKeyword;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.shiro.SecurityUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springside.modules.beanvalidator.BeanValidators;
import org.springside.modules.cache.memcached.SpyMemcachedClient;
import org.springside.modules.mapper.JsonMapper;
import org.springside.modules.utils.Encodes;

import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章 Manager 实现类
 * User: baitao.jibt@gmail.com
 * Date: 12-3-19
 * Time: 下午2:08
 */
@Component
@Transactional(readOnly = true)
public class ArticleManagerImpl implements ArticleManager {

    private static final Logger logger = LoggerFactory.getLogger(ArticleManagerImpl.class);

    private JsonMapper jsonMapper = JsonMapper.nonDefaultMapper();

    private ArticleMapper articleMapper;
    private ArchiveManager archiveManager;
    private CategoryMapper categoryMapper;
    private LinkMapper linkMapper;
    private Validator validator = null;

    /**
     * key list
     * article:id:{id}
     * article:count:all
     * article:count:{categary}
     * article:all:{offset}:{limit}:{sort}:{direction}
     * article:archive:{archiveId}:{offset}:{limit}
     * article:info:19
     * article:category:{categoryId}:{offset}:{limit}
     */
    private SpyMemcachedClient spyMemcachedClient;

    private NotifyMessageProducer notifyProducer; //JMS消息发送

    private FreemakerHelper freemakerHelper;

    @Value("${path.upload.base}")
    private String uploadPath;

    @Value("${paged.article.limit}")
    private int aticleLimit;

    @Override
    public Article get(Long id) {
        Article article = null;
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "id:" + id;
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            article = articleMapper.get(id);
            jsonString = jsonMapper.toJson(article);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            article = jsonMapper.fromJson(jsonString, Article.class);
        }
        long end = System.currentTimeMillis();
        logger.info("获取文章 #{} 用时：{}ms. key: " + key, id, end - start);
        return article;
    }

    @Override
    @Transactional(readOnly = false)
    public Article getForView(Long id) {
        Article article = this.get(id);
        if (null == article) {
            logger.error("ArticleManagerImpl.getForView() 找不到编号为 {} 的文章.", id);
            return null;
        }

        // 记录文章访问次数
        articleMapper.updateViews(id);
        article.setViews(article.getViews() + 1);
        article.setMessage(Encodes.unescapeHtml(article.getMessage()));
        logger.info("更新文章 #{} 的访问次数.", id);
        return article;
    }

    @Override
    public long count() {
        long num = 0L;
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "count:all";
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            num = articleMapper.count();
            jsonString = jsonMapper.toJson(num);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            num = jsonMapper.fromJson(jsonString, Long.class);
        }
        long end = System.currentTimeMillis();
        logger.info("获取文章数 用时：{}ms. key: {}.", end - start, key);
        return num;
    }

    @Override
    public long count(Long categoryId) {
        long num = 0L;
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "count:" + categoryId;
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            num = articleMapper.countByCategoryId(categoryId);
            jsonString = jsonMapper.toJson(num);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            num = jsonMapper.fromJson(jsonString, Long.class);
        }
        long end = System.currentTimeMillis();
        logger.info("获取分类 #{} 的文章数 用时：{}ms. key: " + key, categoryId, end - start);
        return num;
    }

    @Override
    public long count(Long[] ids) {
        long num = 0L;
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "count:" + Arrays.toString(ids).replaceAll(" ", "");
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            num = articleMapper.countByCategoryIds(ids);
            jsonString = jsonMapper.toJson(num);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            num = jsonMapper.fromJson(jsonString, Long.class);
        }
        long end = System.currentTimeMillis();
        logger.info("获取分类 #{} 的文章数 用时：{}ms. key: " + key, Arrays.toString(ids), end - start);
        return num;
    }

    @Override
    public List<Article> getAll() {
        return this.getAll(0, aticleLimit, "id", "DESC");
    }

    @Override
    public List<Article> getAll(int offset, int limit) {
        return this.getAll(offset, limit, "id", "DESC");
    }

    @Override
    public List<Article> getAll(int offset, int limit, String sort, String direction) {
        List<Article> articleList = Lists.newArrayList();
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "all:" + offset + ":" + limit + ":" + sort + ":" + direction + ":false";
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            articleList = articleMapper.getAll(offset, limit, sort, direction, 0);// 包含标记删除和未审核的文章
            jsonString = jsonMapper.toJson(articleList);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            articleList = jsonMapper.fromJson(jsonString, jsonMapper.createCollectionType(List.class, Article.class));
        }
        long end = System.currentTimeMillis();
        logger.info("获取文章列表 用时：{}ms. key: {}.", end - start, key);
        return articleList;
    }

    private List<Article> getTop(int offset, int limit, String sort, String direction) {
        List<Article> articleList = Lists.newArrayList();
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "all:" + offset + ":" + limit + ":" + sort + ":" + direction + ":true";
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            articleList = articleMapper.getAll(offset, limit, sort, direction, 1);// 不包含标记删除和未审核的文章
            jsonString = jsonMapper.toJson(articleList);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            articleList = jsonMapper.fromJson(jsonString, jsonMapper.createCollectionType(List.class, Article.class));
        }
        long end = System.currentTimeMillis();
        logger.info("获取文章列表 用时：{}ms. key: {}.", end - start, key);
        return articleList;
    }

    @Override
    public List<Article> getNewTop(int limit) {
        return this.getTop(0, limit, "id", "DESC");
    }

    @Override
    public List<Article> getHotTop(int limit) {
        return this.getTop(0, limit, "views", "DESC");
    }

    @Override
    public List<Article> getByArchiveId(Long archiveId, int offset, int limit) {
        List<Article> articleList = Lists.newArrayList();
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "archive:" + archiveId + ":" + offset + ":" + limit;
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            articleList = articleMapper.getByArchiveId(archiveId, offset, limit);
            jsonString = jsonMapper.toJson(articleList);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            articleList = jsonMapper.fromJson(jsonString, jsonMapper.createCollectionType(List.class, Article.class));
        }
        long end = System.currentTimeMillis();
        logger.info("获取归档 #{} 的文章 用时：{}ms. key: " + key, archiveId, end - start);
        return articleList;
    }

    @Override
    public List<Article> getByCategoryId(Long categoryId, int offset, int limit) {
        List<Article> articleList = Lists.newArrayList();
        long start = System.currentTimeMillis();
        String key = MemcachedObjectType.ARTICLE.getPrefix() + "category:" + categoryId + ":" + offset + ":" + limit;
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            articleList = articleMapper.getByCategoryId(categoryId, offset, limit);
            jsonString = jsonMapper.toJson(articleList);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            articleList = jsonMapper.fromJson(jsonString, jsonMapper.createCollectionType(List.class, Article.class));
        }
        long end = System.currentTimeMillis();
        logger.info("获取分类 #{} 的文章 用时：{}ms. key: " + key, categoryId, end - start);
        return articleList;
    }

    @Override
    public List<Article> getByCategoryIdAndStatus(Long categoryId, int offset, int limit, boolean status) {
        return articleMapper.getByCategoryIdAndStatus(categoryId, offset, limit, status);
    }

    @Override
    public List<Article> getByCategoryIdAndDeleted(Long categoryId, int offset, int limit, boolean deleted) {
        return articleMapper.getByCategoryIdAndDeleted(categoryId, offset, limit, deleted);
    }

    @Override
    public List<Article> getByCategoryIdAndStatusAndDeleted(Long categoryId, int offset, int limit, boolean status, boolean deleted) {
        return articleMapper.getByCategoryIdAndStatusAndDeleted(categoryId, offset, limit, status, deleted);
    }

    @Override
    public List<Article> getByCategoryIds(Long[] ids, int offset, int limit) {
        List<Article> articleList = Lists.newArrayList();
        long start = System.currentTimeMillis();

        // 数组转字符串
        StringBuffer sb = new StringBuffer(ids[0] + ",");
        for (int i = 1; i < ids.length; i++) {
            sb.append(",");
            sb.append(ids[i]);
        }

        String key = MemcachedObjectType.ARTICLE.getPrefix() + "categorys:" + sb.toString() + ":" + offset + ":" + limit;
        String jsonString = spyMemcachedClient.get(key);

        if (StringUtils.isBlank(jsonString)) {
            articleList = articleMapper.getByCategoryIds(ids, offset, limit);
            jsonString = jsonMapper.toJson(articleList);
            spyMemcachedClient.set(key, MemcachedObjectType.ARTICLE.getExpiredTime(), jsonString);
        } else {
            articleList = jsonMapper.fromJson(jsonString, jsonMapper.createCollectionType(List.class, Article.class));
        }
        long end = System.currentTimeMillis();
        logger.info("获取分类 19、20、21、22、32、33 的文章 用时：{}ms. key: " + key, end - start);
        return articleList;
    }

    @Override
    @Transactional(readOnly = false)
    public int save(Article article) {
        logger.info("保存文章 article={}.", article.toString());
        try {
            // 文章作者
            ShiroDbRealm.ShiroUser shiroUser = (ShiroDbRealm.ShiroUser) SecurityUtils.getSubject().getPrincipal();
            article.setUser(new User(shiroUser.getId()));

            article = this.checkArticle(article);

            int num = articleMapper.save(article);
            generateContent(article);
            return num;
        } catch (ConstraintViolationException cve) {
            logger.warn("操作员 {} 尝试发表文章, 缺少相关字段.", cve.getConstraintViolations().toString());
            return 0;
        }
    }

    @Override
    @Transactional(readOnly = false)
    public int update(Article article) {
        logger.info("更新文章 #{}.", article.toString());
        try {
            article = this.checkArticle(article);

            int num = articleMapper.update(article);
            generateContent(article);
            return num;
        } catch (ConstraintViolationException cve) {
            logger.warn("操作员{}尝试发表文章, 缺少相关字段.", cve.getConstraintViolations().toString());
            return 0;
        }
    }

    private Article checkArticle(Article article) {
        // 生成默认摘要
        String str = Jsoup.parse(article.getMessage()).text();
        if (str.length() > 150) {
            article.setDigest(str.substring(0, 150));
        } else {
            article.setDigest(str);
        }

        // 文章中的首个图片
        String firstImage = this.getImageFromMessage(article.getMessage());
        if (StringUtils.isNotBlank(firstImage)) {
            article.setImageName(firstImage);
        } else {
            article.setImageName("404.jpg");
        }

        if (StringUtils.isBlank(article.getUrl())) {
            article.setUrl("0");
        }

        // 任务生成文章图片
        if (StringUtils.isNotBlank(firstImage)) {
            notifyProducer.sendQueueGenArticleImage(article.getImageName());
        }

        // 进行HTML编码, 否则前台可能破页
        if (StringUtils.isNotBlank(article.getMessage())) {
            article.setMessage(Encodes.escapeHtml(article.getMessage()));
            article.setDigest(Encodes.escapeHtml(article.getDigest()));
        } else {
            article.setMessage("无内容");
        }

        // 关键词由任务生成
        article.setKeyword(this.genKeywords(article, 10));

        //使用Hibernate Validator校验请求参数
        Validate.notNull(article, "文章参数为空");
        BeanValidators.validateWithException(validator, article);
        return article;
    }

    /**
     * 从正文html代码中获得图片路径
     *
     * @param message
     * @return
     */
    private String getImageFromMessage(String message) {
        Document document = Jsoup.parse(message);
        Elements imgs = document.getElementsByTag("img");
        if (null == imgs || imgs.size() == 0) {
            return "";
        } else {
            Element img = imgs.first();
            String srcString = img.attr("src");
            return srcString.substring(srcString.lastIndexOf('/') + 1, srcString.length());
        }
    }

    /**
     * 生成关键词
     *
     * @param article
     * @param num     关键词个数
     */
    private String genKeywords(Article article, int num) {
        ArticleKeyword articleKeyword = new ArticleKeyword();
        String keywords = articleKeyword.getArticleKeyword(article.getSubject(), article.getMessage(), num);
        logger.info("为文章 #{} 生成关键词：{}.", article.getId(), keywords);
        return keywords;
    }

    @Override
    @Transactional(readOnly = false)
    public void batchAudit(String[] ids) {
        logger.info("批量审核文章 #{}.", Arrays.toString(ids));
        for (String id : ids) {
            if (id.length() == 0) {
                continue;
            }
            articleMapper.updateBool(Long.parseLong(id), "status");
        }
    }

    @Override
    @Transactional(readOnly = false)
    public void batchDelete(String[] ids) {
        logger.info("批量删除文章 #{}.", Arrays.toString(ids));
        for (String id : ids) {
            if (id.length() == 0) {
                continue;
            }
            articleMapper.updateBool(Long.parseLong(id), "deleted");
        }
    }

    @Override
    @Transactional(readOnly = false)
    public boolean top(Long id) {
        logger.info("置顶文章 #{}.", id);
        try {
            return articleMapper.updateBool(id, "top") > 0;
        } catch (Exception e) {
            return false;// 防止修改位置id时报错
        }
    }

    @Override
    @Transactional(readOnly = false)
    public boolean audit(Long id) {
        logger.info("审核文章 #{}.", id);
        try {
            return articleMapper.updateBool(id, "status") > 0;
        } catch (Exception e) {
            return false;// 防止修改位置id时报错
        }
    }

    @Override
    @Transactional(readOnly = false)
    public boolean allowComment(Long id) {
        logger.info("允许评论文章 #{}.", id);
        try {
            return articleMapper.updateBool(id, "allow_comment") > 0;
        } catch (Exception e) {
            return false;// 防止修改位置id时报错
        }
    }

    @Override
    @Transactional(readOnly = false)
    public int delete(Long id) {
        logger.info("删除文章 #{}.", id);
        return articleMapper.updateBool(id, "deleted");
    }

    @Override
    @Transactional(readOnly = false)
    public int deleteByTask() {
        logger.info("任务删除文章.");
        Set<String> titleSet = Sets.newHashSet();
        String format = "yyyy年MM月";
        DateTimeFormatter fmt = DateTimeFormat.forPattern(format);

        List<Article> articleList = articleMapper.getDeleted();
        if (null == articleList || articleList.size() == 0) {
            return 0;
        }

        for (Article article : articleList) {
            // 收集归档标题
            titleSet.add(article.getCreatedDate().toString(format));

            // 任务删除文章图片
            notifyProducer.sendQueueDelArticleImage(article.getImageName());
        }

        // 更新相关月份的归档信息
        for (String title : titleSet) {
            archiveManager.updateByMonth(fmt.parseDateTime(title));
        }

        return articleMapper.deleteByTask();
    }

    /**
     * TODO 清理 Memcached 缓存
     private void clearMemcached(String key) {
     spyMemcachedClient.incr(key, 1, 0);
     }
     */

    /**
     * 生成文章静态页面
     */
    private void generateContent(Article article) {
        Map context = Maps.newHashMap();
        DateTime now = new DateTime();
        int year = now.getYear();
        int month = now.getMonthOfYear();
        try {
            String fileNmae = article.getUrl();
            if ("0".equals(fileNmae)) {
                fileNmae = article.getId().toString();
            }
            List<Category> categories = categoryMapper.getNavCategory();
            List<Link> links = linkMapper.getAll(0, Integer.MAX_VALUE, "id", "DESC");
            context.put("categories", categories);
            context.put("article", article);
            context.put("links", links);
            freemakerHelper.generateContent(context, "article.ftl", "../posts/" + year + "/" + month + "/" + fileNmae + ".html");
        } catch (Exception e) {
            logger.error("异常: ", e.getMessage());
        }
    }

    @Autowired
    public void setValidator(@Qualifier("validator") Validator validator) {
        this.validator = validator;
    }

    @Autowired
    public void setArticleMapper(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    @Autowired
    public void setArchiveManager(ArchiveManager archiveManager) {
        this.archiveManager = archiveManager;
    }

    @Autowired
    public void setCategoryMapper(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @Autowired
    public void setLinkMapper(LinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    @Autowired
    public void setSpyMemcachedClient(@Qualifier("spyMemcachedClient") SpyMemcachedClient spyMemcachedClient) {
        this.spyMemcachedClient = spyMemcachedClient;
    }

    @Autowired
    public void setNotifyProducer(NotifyMessageProducer notifyProducer) {
        this.notifyProducer = notifyProducer;
    }

    @Autowired
    public void setFreemakerHelper(FreemakerHelper freemakerHelper) {
        this.freemakerHelper = freemakerHelper;
    }

}
