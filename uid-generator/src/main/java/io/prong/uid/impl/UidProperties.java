package io.prong.uid.impl;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import io.prong.uid.utils.DateUtils;

/**
 * UID 的配置
 * 
 * @author tangyz
 *
 */
@ConfigurationProperties(prefix = "prong.uid")
public class UidProperties {

    /** 时间增量值占用位数。当前时间相对于时间基点的增量值，单位为秒 */
    private int timeBits = 28;
    
    /** 工作机器ID占用的位数 */
    private int workerBits = 22;
    
    /** 序列号占用的位数 */
    private int seqBits = 13;

	/** 时间基点. 例如 2018-11-26 (毫秒: 1543161600000) */
    private String epochStr = "2018-11-26";
    
    /** 时间基点对应的毫秒数 */
    private long epochSeconds = TimeUnit.MILLISECONDS.toSeconds(1543161600000L);
    
	public int getTimeBits() {
		return timeBits;
	}

	public void setTimeBits(int timeBits) {
      if (timeBits > 0) {
    	  this.timeBits = timeBits;
      }
	}

	public int getWorkerBits() {
		return workerBits;
	}

	public void setWorkerBits(int workerBits) {
		if (workerBits > 0) {
			this.workerBits = workerBits;
		}
	}

	public int getSeqBits() {
		return seqBits;
	}

	public void setSeqBits(int seqBits) {
		if (seqBits > 0) {
			this.seqBits = seqBits;
		}
	}
	
	public String getEpochStr() {
		return epochStr;
	}
	
    public void setEpochStr(String epochStr) {
        if (StringUtils.isNotBlank(epochStr)) {
            this.epochStr = epochStr;
            this.epochSeconds = TimeUnit.MILLISECONDS.toSeconds(DateUtils.parseByDayPattern(epochStr).getTime());
        }
    }

	public long getEpochSeconds() {
		return epochSeconds;
	}

}
