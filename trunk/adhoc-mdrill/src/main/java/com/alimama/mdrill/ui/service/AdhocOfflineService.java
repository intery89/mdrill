package com.alimama.mdrill.ui.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import backtype.storm.utils.Utils;


import com.alimama.mdrill.adhoc.MD5;
import com.alimama.mdrill.adhoc.MySqlConn;
import com.alimama.mdrill.adhoc.MysqlCallback;
import com.alimama.mdrill.adhoc.MysqlInfo;
import com.alimama.mdrill.adhoc.OfflineDownload;
import com.alimama.mdrill.json.JSONObject;
import com.alimama.mdrill.partion.GetPartions;
import com.alimama.mdrill.partion.GetShards;
import com.alimama.mdrill.partion.GetPartions.*;
import com.alimama.mdrill.ui.service.utils.WebServiceParams;
import com.alimama.mdrill.utils.HadoopBaseUtils;


public class AdhocOfflineService {
	private static Logger LOG = Logger.getLogger(AdhocOfflineService.class);

	private static String logRequest(String projectName, String callback,
			String startStr, String rowsStr, String queryStr, String dist,
			String fl, String groupby, String sort, String order,String leftjoin) {
		StringBuffer debugBuffer = new StringBuffer();
		debugBuffer.append(projectName).append(",").append(startStr)
				.append(",").append(rowsStr).append(",").append(queryStr)
				.append(",").append(fl).append(",");
		debugBuffer.append(groupby).append(",").append(sort).append(",")
				.append(order).append(",").append(leftjoin).append(",");
		return debugBuffer.toString();
	}

	public static void readHiveResult(String uuid, OutputStreamWriter outStream)
			throws SQLException, IOException {
		Map stormconf = Utils.readStormConfig();
		String hdpConf = (String) stormconf.get("hadoop.conf.dir");
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		MySqlConn conn = new MySqlConn(connstr, uname, passwd);
		Configuration conf = new Configuration();
		HadoopBaseUtils.grabConfiguration(hdpConf, conf);
		MysqlInfo info = new MysqlInfo(conn);
		HashMap<String, String> result = info.get(uuid);
		String exehql = "";
		if (result != null && result.containsKey("storedir")) {
			exehql = result.get("_exehql");
		}
		if (result != null && result.containsKey("storedir")
				&& result.containsKey("cols")) {
			outStream.write(result.get("cols").replaceAll("\001", ",")
					.replaceAll("\t", ",")
					+ "\n");
			AdhocOfflineService.readHiveResult(result.get("storedir"), outStream, conf);
		}
	
		// outStream.write(exehql.replaceAll("\001", ",").replaceAll("\t",
		// "\t")+"\n");
	
	}

	public static void readHiveResult(String path,
			OutputStreamWriter outStream, Configuration conf)
			throws IOException {
		FileSystem fs = FileSystem.get(conf);
		Path dir = new Path(path);
		if (!fs.exists(dir)) {
			throw new IOException("can not found path:" + path);
		}
		FileStatus[] filelist = fs.listStatus(dir);
	
		Long bytesRead = 0l;
		long maxsize = 1024l * 1024 * 1024 * 10;
	
		for (FileStatus f : filelist) {
			if (!f.isDir() && !f.getPath().getName().startsWith("_")) {
				FSDataInputStream in = fs.open(f.getPath());
				BufferedReader bf=new BufferedReader(new InputStreamReader(in)); 
				String line;
				while ((line = bf.readLine()) != null) {
					bytesRead += line.getBytes().length;
					outStream.write(line.replaceAll("\001", ",").replaceAll("\t", ","));
					outStream.write("\r\n");
					if (bytesRead >= maxsize) {
						bf.close();
						in.close();
						return;
					}
				}
				bf.close();
				in.close();
			}
		}
		return;
	}

	public static String offline(String projectName, String jsoncallback,
			String startStr, String rowsStr, String queryStr, String dist,
			String fl, String groupby, String sort, String order,
			String mailto, String username, String jobname, String params)
			throws Exception {
		long t1 = System.currentTimeMillis();
	
		boolean isPartionByPt = false;
		if (projectName.equals("rpt_hitfake_auctionall_d")) {
			projectName = "rpt_p4padhoc_auction";
		}
		if (projectName.equals("rpt_b2bad_hoc_memb_sum_d")) {
			isPartionByPt = true;
		}
		queryStr = WebServiceParams.query(queryStr);
	
		String sqlSort = WebServiceParams.sortHive(sort, order);
	
		TablePartion part = GetPartions.partion(projectName);
		String[] cores = GetShards.get(part.name, false, 10000);
		String[] ms = GetShards.get(part.name, true, 10000);
		String[] partionsAll = GetPartions.get(queryStr, part.parttype);
		GetPartions.Shards shard = GetPartions.getshard(part, partionsAll,
				cores, ms, 10000, 0);
	
		HashMap<String, String> filetypeMap = MdrillService.readFieldsFromSchemaXml(part.name);
		ArrayList<String> fqList = WebServiceParams.fqListHive(queryStr, shard,
				isPartionByPt, filetypeMap);
		StringBuffer sqlWhere = new StringBuffer();
		String join = " where ";
		for (String fq : fqList) {
			sqlWhere.append(join);
			sqlWhere.append(fq);
			join = " and ";
		}
	
		ArrayList<String> groupFields = WebServiceParams.groupFields(groupby);
		StringBuffer sqlGroup = new StringBuffer();
		String daycols="";
		join = " group by ";
		for (String field : groupFields) {
			sqlGroup.append(join);
			sqlGroup.append(field);
			if(field.equals("thedate"))
			{
				daycols="����,";
			}
			join = ",";
		}
	
		ArrayList<String> showFields = WebServiceParams.showHiveFields(fl);
		StringBuffer cols = new StringBuffer();
		join = "";
		for (String field : groupFields) {
			cols.append(join);
			cols.append(field);
			join = ",";
		}
		for (String field : showFields) {
			if(field.equals("thedate"))
			{
				daycols="����,";
			}
			if(!groupFields.contains(field))
			{
				cols.append(join);
				cols.append(field);
				join = ",";
			}
		}
	
		String hql = "select " + cols.toString() + " from " + projectName + " "
				+ sqlWhere.toString() + " " + sqlGroup.toString() + " "
				+ sqlSort + "";
	
		String md5 = MD5.getMD5(hql);
	
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String day = fmt.format(new Date());
	
		Map stormconf = Utils.readStormConfig();
		String hdpConf = (String) stormconf.get("hadoop.conf.dir");
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		String store = (String) stormconf.get("higo.download.offline.store")
				+ "/" + day + "/" + java.util.UUID.randomUUID().toString();
		MySqlConn conn = new MySqlConn(connstr, uname, passwd);
		MysqlInfo info = new MysqlInfo(conn);
		if (username == null || username.length() <= 0) {
			username = "default";
		}
	
		StringBuffer sqlbuff = new StringBuffer();
		// int jobsize=info.getUserJobname(username, jobname, sqlbuff).size();
		sqlbuff.append(";");
		int size = info.getUser(username, true, sqlbuff).size();
		JSONObject jsonObj = new JSONObject();
		jsonObj.put("sqlbuff", sqlbuff.toString());
		jsonObj.put("size", size);
		// if(jobsize>0)
		// {
		// jsonObj.put("code", "0");
		// jsonObj.put("message", "֮ǰ�Ѿ��н�"+jobname+"������,�뻻������");
		// }else
		//
		if (size < 5) {
			MysqlCallback callback = new MysqlCallback(conn);
	
			String[] pcols = params == null ? new String[0] : new String(daycols
					+ params.replaceAll("ά��ָ�꣺", "").replaceAll("��.*$", ""))
					.split(",");
			StringBuffer liststat = new StringBuffer();
			StringBuffer listgroup = new StringBuffer();
			for (String s : pcols) {
				if (AdhocOfflineService.isStatFields(s)) {
					liststat.append(s);
					liststat.append(",");
				} else {
					listgroup.append(s);
					listgroup.append(",");
				}
			}
	
			callback.setCols((params == null || params.isEmpty()) ? cols
					.toString() : listgroup.toString() + liststat.toString());
			OfflineDownload download = new OfflineDownload();
			download.setOffline(callback);
			if (mailto == null || mailto.length() <= 0) {
				mailto = "yannian.mu@alipay.com";
			}
	
			download.setMailto(mailto);
			download.setHql("INSERT OVERWRITE DIRECTORY '" + store + "' " + hql);
	
			download.setUseName(username);
			if (jobname == null || jobname.length() <= 0) {
				jobname = day + "_" + md5;
			}
			download.setJobName(jobname);
			download.setDisplayParams((params == null || params.isEmpty()) ? hql
					: params);
			download.setStoreDir(store);
			download.setConfdir(hdpConf);
			download.setSqlMd5(md5);
			download.run();
	
			long t2 = System.currentTimeMillis();
			jsonObj.put("code", "1");
	
			jsonObj.put("message",
					"���������...��ɺ󽫻�ͨ��<b style=\"color:red\">����</b>��<b style=\"color:red\">�ʼ�</b>֪ͨ");
			jsonObj.put("uuid", callback.getUuid());
			jsonObj.put("debug", callback.toString());
			jsonObj.put("timedebug", String.valueOf(t2 - t1));
	
		} else {
			jsonObj.put("code", "0");
			jsonObj.put("message", "����" + size + "������û�����������أ����Ժ��ύ");
		}
	
		if (jsoncallback != null && jsoncallback.length() > 0) {
			return jsoncallback + "(" + jsonObj.toString() + ")";
		} else {
			return jsonObj.toString();
		}
	
	}

	public static boolean isStatFields(String field)
	{
		if(field.indexOf("���(")>=0)
		{
			return true;
		}
		if(field.indexOf("count(")>=0)
		{
			return true;
		}
		if(field.indexOf("ƽ��ֵ(")>=0)
		{
			return true;
		}
		
		if(field.indexOf("���ֵ(")>=0)
		{
			return true;
		}
		
		if(field.indexOf("��Сֵ(")>=0)
		{
			return true;
		}
		
		if(field.indexOf("����(")>=0)
		{
			return true;
		}
		
		if(field.indexOf("dist(")>=0)
		{
			return true;
		}
		return false;
	}
}
