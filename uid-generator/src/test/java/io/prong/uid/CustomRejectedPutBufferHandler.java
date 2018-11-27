package io.prong.uid;

import org.springframework.stereotype.Component;

import io.prong.uid.buffer.RejectedPutBufferHandler;
import io.prong.uid.buffer.RingBuffer;

/**
 * 自定义的拒绝策略: 当环已满, 无法继续填充时
 * 
 * @author tangyz
 *
 */
@Component
public class CustomRejectedPutBufferHandler implements RejectedPutBufferHandler {

	/**
	 * 只打印，不记日志
	 */
	@Override
	public void rejectPutBuffer(RingBuffer ringBuffer, long uid) {
		System.out.format("Rejected putting buffer for uid:{%d}. {%s}\r\n", uid, ringBuffer.toString());
	}

}
