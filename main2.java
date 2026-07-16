/*class example2{
  public static void main (String[] ards){
    int myVar1;
    int myVar2;

    myVar1 = 1024;
    System.out.println("myVar1 содержит " + myVar1);

    myVar2 = myVar1/2;

    System.out.print("myVar2 содержит myvar1/2: ");
    System.out.println(myVar2);
  }
}*/

/*
class example3{
  public static void main(String[] args){
    int v;
    double x;
    v = 10;
    x = 10.0;

    System.out.println("1_v: " + v);
    System.out.println("2_x: " + x);
    System.out.println();

    v = v / 4;
    x = x / 4;
    System.out.println("3_v: " + v);
    System.out.println("4_x: " + x);
  }
}*/
/*
class galToList{
  public static void main(String[] args){
    double qallons;
    double liters;
    double one_liter = 3.7854;

    qallons = 10;

    liters = qallons * one_liter;

    System.out.println(qallons + " галлонов соответствует " + liters + " литрам");
  }
}*/
/*
class if_demo{
  public static void main(String[] args){
  int a, b, c;

  a = 2;
  b = 3;

  if (a<b) System.out.println("a<b");
  if (a == b) System.out.println("не увижу");

    System.out.println();
  c = a-b;

  System.out.println("переменная C содержит -1");
  if (c>=0) System.out.println("Значение C неотрицательное");
  if(c<0) System.out.println("значение C отрицательное");

    System.out.println();
    c = b - a;

    System.out.println ("Переменная содержит 1");
    if(c>=0) System.out.println("значение C неотрицательное");
    if(c<0) System.out.println("Значение C отрицательное");
  }
}*/
/*
class for_demo{
  public static void main(String[] args){
    int count;

    for(count = 0; count < 5; count++){
      System.out.println("значение count: " + count);
    }
    System.out.println("готово");
  }
}
*/

/*
class gal_to_lit_table{
  public static void main(String[] args){
    double gallons, liters;
    double gallon = 3.7854;
    int counter = 0;

    for(gallons = 1; gallons <= 100; gallons++){
      liters = gallons * gallon;
      System.out.println(gallons + " галлонов соответствует " + liters + " литрам");
      counter++;
      if(counter == 10){
        System.out.println();
        counter = 0;
      }
    }
  }
}*/

class fut_to_metr{
  public static void main(String[] args){
    double metrs, futs;
    double metr = 39.37;
    int counter = 0;

    for(metrs = 1; metrs <= 12; metrs++){
      futs = metrs * metr;
      System.out.println(metrs + " метров соответствует " + futs + " футам");
      counter++;
      if(counter == 10){
        System.out.println();
        counter = 0;
      }
    }
  }
}