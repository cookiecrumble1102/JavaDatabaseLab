import java.util.HashMap;

// Нужен для выгрузки в оперативку при использовании GUI
// В остальном через него не делаем ничего, т.к очень много ест оперативной памяти
public class Record 
{
    private HashMap<String, Object> values = new HashMap<String, Object>();


    public Record(HashMap<String, Object> values)
    {
        if (values == null) {
            this.values = new HashMap<>();
        } 
        else {
            this.values = new HashMap<>(values);
        }
    }

    public Object getValue(String name)
    {
        Object item = this.values.get(name);
        if (item == null)
        {
            // Можно заменить на return null;
            throw new IllegalArgumentException("В записи отсутствует значение по полю: " + name);
        }
        return item;
    }

    public HashMap<String, Object> getValues()
    {
        return this.values;
    }
    
}
