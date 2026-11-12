public class Range <T extends Number> {
    private T low;
    private T high;
    private boolean includeLow;
    private boolean includeHigh;

    public Range(T low, T high, boolean includeLow, boolean includeHigh) 
    {
        this.low = low;
        this.high = high;
        this.includeLow = includeLow;
        this.includeHigh = includeHigh;
    }

    public boolean checkCondition(Number value) 
    {
        double val = value.doubleValue();

        if (this.low != null) 
        {
            double lw = this.low.doubleValue();
            if (includeLow ? val < lw : val <= lw) return false;
        }

        if (this.high != null) 
        {
            double hg = this.high.doubleValue();
            if (includeHigh ? val > hg : val >= hg) return false;
        }

        return true;
    }

    // Геттеры
    public T getLow() {return this.low;}
    public T getHigh() {return this.high;}
    public boolean isLowIncluded() {return this.includeLow;}
    public boolean isHighIncluded() {return this.includeHigh;}

}