package com.kflow.erp.hr.application;
import java.util.List;
import com.kflow.erp.hr.api.HrFailure;
import static com.kflow.erp.hr.api.HrFailure.Code.INVALID_PAGE;
public record HrPage<T>(List<T> items, boolean hasNext) {
    public HrPage { items=List.copyOf(items); }
    public static int size(Integer size) {
        int n=size==null?20:size;
        if(n<1 || n>100) throw new HrFailure(INVALID_PAGE);
        return n;
    }
    public static int offset(int page, int size) {
        long offset=(long)page*size;
        if(page<0 || offset>Integer.MAX_VALUE-size-1) throw new HrFailure(INVALID_PAGE);
        return (int)offset;
    }
    public static <T> HrPage<T> of(List<T> rows,int size) {
        return new HrPage<>(rows.subList(0,Math.min(rows.size(),size)),rows.size()>size);
    }
}
