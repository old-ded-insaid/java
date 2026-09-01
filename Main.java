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

class vehicle{
  int passengers;
  int fuelcap;
  int mpg;
  int range(){
    return mpg*fuelcap;
  }
}

class vehicle_demo{
  public static void main(String[] args) {
    vehicle minivam = new vehicle();
    int range;
    minivam.fuelcap = 16;
    minivam.mpg = 21;
    minivam.passengers = 21;
    range = minivam.fuelcap * minivam.mpg;
    System.out.println("Минивен может перевезти " + minivam.passengers + " человек на расстояние " + range + " миль");
  }
}

class twovehicles{
  public  static void main(String[] args){
    vehicle minivan = new vehicle();
    vehicle sportscar = new vehicle();

    int range1, range2;
    minivan.fuelcap = 16;
    minivan.mpg = 21;
    minivan.passengers = 7;

    sportscar.fuelcap = 14;
    sportscar.mpg = 12;
    sportscar.passengers = 2;

    range1 = minivan.fuelcap * minivan.mpg;
    range2 = sportscar.fuelcap * sportscar.mpg;

    System.out.println("минивэн может провезти " + minivan.passengers + " пассажиров на расстояние " + range1 + " миль");
    System.out.println("спорткар может провезти " + sportscar.passengers + " пассажиров на расстояние " + range2 + " миль");

  }
}


class retmet{
  public static void main(String[] args){
    vehicle minivan = new vehicle();
    vehicle sportscar = new vehicle();
    int range1, range2;
    minivan.passengers = 7;
    minivan.fuelcap = 16;
    minivan.mpg=21;
    sportscar.passengers = 1;
    sportscar.fuelcap = 14;
    sportscar.mpg = 12;
    range1 = minivan.range();
    range2 = sportscar.range();

    System.out.println("минивэн может провезти " + minivan.passengers + " пассажиров на расстояние " + range1 + " миль");
    System.out.println("спорткар может провезти " + sportscar.passengers + " пассажиров на расстояние " + range2 + " миль");

  }
}

class chkNum{
  boolean isEven(int x){
    if((x%2) == 0) return true;
    else return false;
  }
}
class parmdemo{
  public static void main(String[] args) {
    chkNum e = new chkNum();
    if(e.isEven(10)) System.out.println("10 - четное");
    if(e.isEven(9)) System.out.println("9 - нечетное");
    if(e.isEven(8)) System.out.println("8 - четное");
  }
}

class Factor {
  boolean isFactor(int a, int b) {
    return b % a == 0;
  }
}

class IsFact {
  public static void main(String[] args) {
    Factor x = new Factor();

    if (x.isFactor(2, 20)) {
      System.out.println("2 - множитель");
    }
    if (!x.isFactor(3, 20)) {
      System.out.println("не отобразится");
    }
  }
}