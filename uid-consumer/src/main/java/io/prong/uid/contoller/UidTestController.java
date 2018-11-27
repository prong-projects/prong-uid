package io.prong.uid.contoller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.prong.uid.service.UidGenService;

/**
 * UID 测试web服务
 * 
 * @author tangyz
 *
 */
@RestController
public class UidTestController {

	@Autowired
	private UidGenService uidGenService;

	/**
	 * 测试预先缓存UID的生成器服务：http://localhost:9999/testcacheduid
	 * 
	 * @return
	 */
	@GetMapping("/testcacheduid")
	public String test() {
		return String.valueOf(uidGenService.getCachedUid());
	}

	/**
	 * 测试UID的生成器服务：http://localhost:9999/testdefaultuid
	 * 
	 * @return
	 */
	@GetMapping("/testdefaultuid")
	public String test2() {
		return String.valueOf(uidGenService.getDefaultUid());
	}

}
