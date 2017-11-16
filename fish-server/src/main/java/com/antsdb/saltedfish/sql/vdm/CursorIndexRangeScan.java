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
package com.antsdb.saltedfish.sql.vdm;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.antsdb.saltedfish.nosql.GTable;
import com.antsdb.saltedfish.nosql.Row;
import com.antsdb.saltedfish.nosql.RowIterator;
import com.antsdb.saltedfish.nosql.SpaceManager;
import com.antsdb.saltedfish.sql.meta.IndexMeta;
import com.antsdb.saltedfish.sql.meta.TableMeta;
import com.antsdb.saltedfish.sql.planner.SortKey;

/**
 * cursor driven index range scan. this is an optimization for in select
 * 
 * @author wgu0
 */
public class CursorIndexRangeScan extends CursorMaker {
    TableMeta table;
    IndexMeta index;
    CursorMeta meta;
    int[] mapping;
    private CursorMaker select;
    private Vector prefix;
    boolean isAsc=true;

    class MyCursor extends CursorWithHeap {
        Cursor select;
        long[] values;
        RowIterator iter;
        boolean isClosed = false;
        private AtomicLong counter;
        GTable gtable;
        Transaction trx;
        private GTable gindex;

        public MyCursor(SpaceManager memman, GTable gtable, GTable gindex, Transaction trx, AtomicLong counter) {
            super(CursorIndexRangeScan.this);
            this.gtable = gtable;
            this.gindex = gindex;
            this.trx = trx;
            this.counter = counter;
        }

        @Override
        public long next() {
            for (;;) {
                if (isClosed) {
                    return 0;
                }

                // next scan

                if (this.iter == null) {
                    nextScan();
                    continue;
                }

                // next record

                if (!this.iter.next()) {
                    this.iter.close();
                    this.iter = null;
                    continue;
                }

                // convert row to record

                long pRecord = newRecord();
                long pRowKey = iter.getRowKeyPointer();
                if (pRowKey == 0) {
                    continue;
                }
                Row row = gtable.getRow(trx.getTrxId(), trx.getTrxTs(), pRowKey);
                Record.setKey(pRecord, row.getKeyAddress());
                for (int i = 0; i < this.meta.getColumnCount(); i++) {
                    long pValue = row.getFieldAddress(CursorIndexRangeScan.this.mapping[i]);
                    Record.set(pRecord, i, pValue);
                }
                this.counter.incrementAndGet();
                return pRecord;
            }
        }

        private void nextScan() {
            // fetch next value from cursor

            long pRec = this.select.next();
            if (pRec == 0) {
                close();
                return;
            }
            long pValue = Record.get(pRec, 0);

            // calculate key

            this.values[this.values.length - 1] = pValue;
            KeyMaker keymaker = CursorIndexRangeScan.this.index.getKeyMaker();
            long pFrom = keymaker.make(getHeap(), values);
            long pTo = keymaker.makeMax(getHeap(), values);

            // scan !!

            boolean isAsc = CursorIndexRangeScan.this.isAsc;
            this.iter = gindex.scan(trx.getTrxId(), trx.getTrxTs(), pFrom, true, pTo, true, isAsc);
        }

        @Override
        public void close() {
            this.isClosed = true;
            super.close();
            this.select.close();
        }
    }

    public CursorIndexRangeScan(TableMeta table, IndexMeta index, int makerId) {
        this.table = table;
        this.index = index;
        this.meta = CursorMeta.from(table);
        this.mapping = this.meta.getHumpbackMapping();
        setMakerId(makerId);
    }

    @Override
    public CursorMeta getCursorMeta() {
        return this.meta;
    }

    @Override
    public Object run(VdmContext ctx, Parameters params, long pMaster) {
        GTable gindex = ctx.getHumpback().getTable(index.getIndexTableId());
        GTable gtable = ctx.getHumpback().getTable(table.getHtableId());
        Transaction trx = ctx.getTransaction();
        MyCursor c = new MyCursor(ctx.getSpaceManager(), gtable, gindex, trx, ctx.getCursorStats(makerId));
        boolean success = false;
        try {
            c.select = this.select.make(ctx, params, pMaster);
            c.values = new long[this.prefix.getValues().size() + 1];
            for (int i = 0; i < this.prefix.getValues().size(); i++) {
                c.values[i] = this.prefix.getValues().get(i).eval(ctx, c.getHeap(), params, pMaster);
            }
            success = true;
            return c;
        }
        finally {
            if (!success) {
                c.close();
            }
        }
    }

    @Override
    public String toString() {
        return "Cursor Index Scan (" + this.table.getObjectName() + ") (" + this.index.getName() + ")";
    }

    @Override
    public void explain(int level, List<ExplainRecord> records) {
        ExplainRecord rec = new ExplainRecord(level, toString());
        rec.setMakerId(this.makerId);
        records.add(rec);
        this.select.explain(level + 1, records);
    }

    public void setKey(Vector v, CursorMaker select) {
        this.prefix = v;
        this.select = select;
    }

    @Override
    public boolean setSortingOrder(List<SortKey> order) {
        int sort = SortKey.follow(SortKey.from(this.table, this.index), order);
        switch (sort) {
        case 1:
            this.isAsc = true;
            return true;
        case -1:
            this.isAsc = false;
            return true;
        default:
            return false;
        }
    }
}
