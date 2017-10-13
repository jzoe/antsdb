/*-------------------------------------------------------------------------------------------------
 _______ __   _ _______ _______ ______  ______
 |_____| | \  |    |    |______ |     \ |_____]
 |     | |  \_|    |    ______| |_____/ |_____]

 Copyright (c) 2016, antsdb.com and/or its affiliates. All rights reserved. *-xguo0<@

 This program is free software: you can redistribute it and/or modify it under the terms of the
 GNU Affero General Public License, version 3, as published by the Free Software Foundation.

 You should have received a copy of the GNU Affero General Public License along with this program.
 If not, see <https://www.gnu.org/licenses/agpl-3.0.txt>
-------------------------------------------------------------------------------------------------*/
package com.antsdb.saltedfish.storage;

import java.io.IOException;

import org.apache.commons.lang.NotImplementedException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;

import com.antsdb.saltedfish.cpp.BluntHeap;
import com.antsdb.saltedfish.cpp.Heap;
import com.antsdb.saltedfish.cpp.KeyBytes;
import com.antsdb.saltedfish.nosql.Row;
import com.antsdb.saltedfish.nosql.ScanResult;
import com.antsdb.saltedfish.nosql.StorageTable;
import com.antsdb.saltedfish.nosql.SysMetaRow;
import com.antsdb.saltedfish.sql.meta.TableMeta;
import com.antsdb.saltedfish.sql.vdm.KeyMaker;
import com.antsdb.saltedfish.util.UberUtil;

/**
 * 
 * @author *-xguo0<@
 */
class HBaseTable implements StorageTable {
    private static Logger _log = UberUtil.getThisLogger();
    private static ThreadLocal<Heap> _heap = new ThreadLocal<>();

    private HBaseStorageService hbase;
    private TableName tn;
    private int tableId;
    SysMetaRow meta;

    static class MyScanResult extends ScanResult {

        private ResultScanner rs;
        private long pRow;
        private int rowScanned;
        private BluntHeap heap;
        private boolean eof = false;
        private TableMeta meta;
        private int tableId;

        public MyScanResult(TableMeta meta, ResultScanner rs, int tableId) {
            this.rs = rs;
            this.meta = meta;
            this.heap = new BluntHeap();
            this.tableId = tableId;
        }

        @Override
        public boolean next() {
            try {
                Result r = this.rs.next();
                if (r == null) {
                    this.pRow = 0;
                    this.eof = true;
                    return false;
                }
                if (r.isEmpty()) {
                    this.pRow = 0;
                    this.eof = true;
                    return false;
                }
                this.rowScanned++;
                this.heap.reset(0);
                this.pRow = Helper.toRow(heap, r, meta, this.tableId);
                return true;
            }
            catch (Exception x) {
                throw new OrcaHBaseException(x);
            }
        }

        @Override
        public boolean eof() {
            return this.eof;
        }

        @Override
        public void close() {
            this.heap.free();
        }

        @Override
        public long getVersion() {
            if (this.pRow == 0) {
                return 0;
            }
            long version = Row.getVersion(this.pRow);
            return version;
        }

        @Override
        public long getKeyPointer() {
            if (pRow == 0) {
                return 0;
            }
            return Row.getKeyAddress(pRow);
        }

        @Override
        public long getIndexRowKeyPointer() {
            return 0;
        }

        @Override
        public long getRowPointer() {
            return this.pRow;
        }

        @Override
        public byte getMisc() {
            return 0;
        }

        @Override
        public void rewind() {
        }

        @Override
        public String toString() {
            return "hbase:" + KeyBytes.toString(getKeyPointer());
        }
    }

    public HBaseTable(HBaseStorageService hbase, SysMetaRow meta) {
        this.meta = meta;
        this.tableId = meta.getTableId();
        this.hbase = hbase;
        this.tn = TableName.valueOf(meta.getNamespace(), meta.getTableName());
    }

    @Override
    public long get(long pKey) {
        if (_log.isTraceEnabled()) {
            _log.trace("get {} {}", this.tn.toString(), KeyBytes.toString(pKey));
        }
        try {
            TableMeta meta = this.hbase.getTableMeta(this.tableId);
            Table htable = getConnection().getTable(tn);
            Get get = new Get(Helper.antsKeyToHBase(pKey));
            Result r = htable.get(get);
            long pRow = Helper.toRow(getHeap(), r, meta, this.tableId);
            return pRow;
        }
        catch (IOException x) {
            throw new OrcaHBaseException(x);
        }
    }

    @Override
    public long getIndex(long pKey) {
        if (_log.isTraceEnabled()) {
            _log.trace("getIndex {} {}", this.tn.toString(), KeyBytes.toString(pKey));
        }
        throw new NotImplementedException();
    }

    @Override
    public ScanResult scan(long pKeyStart, boolean incStart, long pKeyEnd, boolean incEnd, boolean isAscending) {
        if (_log.isTraceEnabled()) {
            _log.trace("scan {} {}{}-{}{}", 
                    this.tn.toString(), 
                    KeyBytes.toString(pKeyStart),
                    incStart ? "" : "ˉ",
                    KeyBytes.toString(pKeyEnd),
                    incEnd ? "" : "ˉ");
        }
        try {
            Scan scan = new Scan();
            byte[] start = null;
            if (pKeyStart != 0) {
                start = KeyBytes.get(pKeyStart);
                KeyMaker.flipEndian(start);
                if (!incStart) {
                    start = appendZero(start);
                }
                scan.setStartRow(start);
            }
            if (pKeyEnd != 0) {
                byte[] end = KeyBytes.get(pKeyEnd);
                KeyMaker.flipEndian(end);
                if (incEnd) {
                    end = appendZero(end);
                }
                scan.setStopRow(end);
            }
            scan.setReversed(!isAscending);
            Table htable = getConnection().getTable(tn);
            ResultScanner rs = htable.getScanner(scan);
            TableMeta meta = this.hbase.getTableMeta(this.tableId);
            if (!isAscending && !incStart && (start != null)) {
                rs = new HBaseReverseScanBugStomper(rs, start);
            }
            ScanResult result = new MyScanResult(meta, rs, this.tableId);
            return result;
        }
        catch (IOException x) {
            throw new OrcaHBaseException(x);
        }
    }

    private byte[] appendZero(byte[] value) {
        byte[] result = new byte[value.length + 1];
        System.arraycopy(value, 0, result, 0, value.length);
        return result;
    }

    private Connection getConnection() {
        return this.hbase.hbaseConnection;
    }

    @Override
    public String toString() {
        return this.tn.toString();
    }

    private Heap getHeap() {
        Heap result = _heap.get();
        if (result == null) {
            result = new BluntHeap();
            _heap.set(result);
        }
        result.reset(0);
        return result;
    }

    @Override
    public void delete(long pKey) {
        throw new NotImplementedException();
    }

    @Override
    public void putIndex(long pIndexKey, long pRowKey, byte misc) {
        throw new NotImplementedException();
    }

    @Override
    public void put(Row row) {
        throw new NotImplementedException();
    }

    @Override
    public boolean exist(long pKey) {
        return get(pKey) != 0;
    }

    @Override
    public String getLocation(long pKey) {
        return "hbase:" + KeyBytes.toString(pKey);
    }
}
